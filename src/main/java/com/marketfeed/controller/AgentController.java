package com.marketfeed.controller;

import com.marketfeed.model.AgentRequest;
import com.marketfeed.model.AgentResponse;
import com.marketfeed.model.BriefingRequest;
import com.marketfeed.model.BriefingResponse;
import com.marketfeed.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "Agentic market analysis powered by Claude")
public class AgentController {

    private final AgentService agentService;

    // ── Rate limiting ────────────────────────────────────────────────────────────
    private static final int    MAX_REQUESTS_PER_HOUR = 10;
    private static final int    MAX_QUESTION_LENGTH   = 500;
    private static final int    MAX_HISTORY_TURNS     = 6;   // last N turns sent to Claude
    private static final long   WINDOW_MS             = 60 * 60 * 1000L; // 1 hour

    /** IP → timestamps of recent requests within the rolling window. */
    private final ConcurrentHashMap<String, Deque<Long>> rateLimitMap = new ConcurrentHashMap<>();

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** Returns true if the IP has exceeded the hourly limit. */
    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = rateLimitMap.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            // Drop entries older than the window
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_REQUESTS_PER_HOUR) {
                return true;
            }
            timestamps.addLast(now);
            return false;
        }
    }

    // ── Endpoints ────────────────────────────────────────────────────────────────

    @GetMapping("/suggestions")
    @Operation(summary = "Get AI-generated daily question suggestions")
    public ResponseEntity<List<String>> getSuggestions() {
        return ResponseEntity.ok(agentService.getDailySuggestions());
    }

    @PostMapping("/briefing")
    @Operation(summary = "Get AI briefing for a watchlist")
    public ResponseEntity<BriefingResponse> briefing(@RequestBody BriefingRequest request,
                                                      HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        if (isRateLimited(ip)) {
            log.warn("Rate limit hit on /briefing from {}", ip);
            return ResponseEntity.status(429).body(
                    BriefingResponse.builder().error("Rate limit reached — try again later (10 requests/hour).").build());
        }
        if (request.getSymbols() == null || request.getSymbols().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    BriefingResponse.builder().error("symbols list is required").build());
        }
        BriefingResponse response = agentService.getBriefing(request.getSymbols());
        if (response.getError() != null) {
            return ResponseEntity.status(503).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/query")
    @Operation(summary = "Ask a natural language question about markets")
    public ResponseEntity<AgentResponse> query(@RequestBody AgentRequest request,
                                               HttpServletRequest httpReq) {
        // 1. Rate limit
        String ip = clientIp(httpReq);
        if (isRateLimited(ip)) {
            log.warn("Rate limit hit on /query from {}", ip);
            return ResponseEntity.status(429).body(
                    AgentResponse.builder().error("Rate limit reached — you can ask 10 questions per hour.").build());
        }

        // 2. Input length guard
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(
                    AgentResponse.builder().error("question is required").build());
        }
        if (request.getQuestion().length() > MAX_QUESTION_LENGTH) {
            return ResponseEntity.badRequest().body(
                    AgentResponse.builder().error(
                        "Question too long — please keep it under " + MAX_QUESTION_LENGTH + " characters.").build());
        }

        // 3. Truncate history to last N turns to cap token usage
        if (request.getHistory() != null && request.getHistory().size() > MAX_HISTORY_TURNS) {
            request.setHistory(
                request.getHistory().subList(
                    request.getHistory().size() - MAX_HISTORY_TURNS,
                    request.getHistory().size()
                )
            );
        }

        AgentResponse response = agentService.query(request);
        if (response.getError() != null && response.getMarketContext() == null) {
            return ResponseEntity.status(503).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
