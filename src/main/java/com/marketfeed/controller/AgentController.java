package com.marketfeed.controller;

import com.marketfeed.model.AgentRequest;
import com.marketfeed.model.AgentResponse;
import com.marketfeed.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "Agentic market analysis powered by Claude")
public class AgentController {

    private final AgentService agentService;

    @GetMapping("/suggestions")
    @Operation(
        summary = "Get AI-generated daily question suggestions",
        description = "Returns 6 questions generated from today's market headlines. Refreshes every 4 hours."
    )
    public ResponseEntity<List<String>> getSuggestions() {
        return ResponseEntity.ok(agentService.getDailySuggestions());
    }

    @PostMapping("/query")
    @Operation(
        summary = "Ask a natural language question about markets",
        description = """
            Pulls live market data relevant to your question, then asks Claude to analyze it.

            Examples:
            - "What's driving corn futures up this week?"
            - "How is the dollar index affecting gold right now?"
            - "Give me a hollistic snapshot of energy markets today."
            - "Is the VIX showing elevated risk?"

            Optionally pass `symbols` to pin which tickers get pulled into context.
            """
    )
    public ResponseEntity<AgentResponse> query(@RequestBody AgentRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(
                    AgentResponse.builder().error("question is required").build());
        }
        AgentResponse response = agentService.query(request);
        if (response.getError() != null && response.getMarketContext() == null) {
            return ResponseEntity.status(503).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
