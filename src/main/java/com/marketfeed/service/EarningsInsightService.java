package com.marketfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.EarningsInsight;
import com.marketfeed.model.FilingInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsInsightService {

    private final SecEdgarService edgarService;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    objectMapper;

    @Value("${market-feed.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${market-feed.anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    @Cacheable(value = "research-earnings-insights", key = "#symbol")
    public EarningsInsight getEarningsInsights(String symbol) {
        List<FilingInfo> filings = edgarService.getFilings(symbol, "8-K", 8);
        if (filings.isEmpty()) {
            return EarningsInsight.builder()
                    .symbol(symbol)
                    .error("No 8-K filings found on SEC EDGAR for " + symbol)
                    .generatedAt(Instant.now())
                    .build();
        }

        List<Map<String, String>> releases = new ArrayList<>();
        for (FilingInfo f : filings) {
            String text = edgarService.fetchEarningsText(f);
            if (text.length() > 400) {
                releases.add(Map.of("date", f.getFilingDate(), "text", text));
            }
            if (releases.size() >= 4) break;
        }

        if (releases.isEmpty()) {
            return EarningsInsight.builder()
                    .symbol(symbol)
                    .error("Could not extract earnings release content from 8-K filings")
                    .generatedAt(Instant.now())
                    .build();
        }

        return analyzeWithClaude(symbol, releases);
    }

    private EarningsInsight analyzeWithClaude(String symbol, List<Map<String, String>> releases) {
        StringBuilder ctx = new StringBuilder();
        for (int i = releases.size() - 1; i >= 0; i--) {
            Map<String, String> r = releases.get(i);
            ctx.append("=== FILING DATE: ").append(r.get("date")).append(" ===\n")
               .append(r.get("text")).append("\n\n");
        }

        String prompt = """
                You are a senior equity analyst reading quarterly 8-K earnings press releases for %s.
                Here are the %d most recent filings, oldest first:

                %s

                Analyze as a set to find patterns, trends, and narrative shifts over time.
                Return ONLY a valid JSON object, no other text:
                {
                  "analyzedPeriods": ["list the filing dates covered, newest first"],
                  "recurringThemes": ["3-4 topics management consistently emphasizes — be specific to this company"],
                  "focusShifts": ["2-3 concrete things management started or stopped emphasizing recently"],
                  "growthDrivers": ["3-4 specific revenue or growth drivers repeatedly mentioned"],
                  "headwinds": ["2-3 persistent challenges management acknowledges each period"],
                  "sentimentTrend": "improving|declining|stable",
                  "guidancePattern": "One sentence on how management frames the outlook — cite specific language patterns they use",
                  "summary": "3-4 sentences on the company narrative arc across these filings and what it signals"
                }
                sentimentTrend must be exactly one of: improving, declining, stable.
                Be specific — cite product names, geographies, metrics, or initiatives actually mentioned.
                """.formatted(symbol, releases.size(), ctx);

        try {
            String raw = callClaude(prompt, 1500);
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s == -1 || e == -1) throw new RuntimeException("No JSON in Claude response");

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw.substring(s, e + 1), Map.class);

            return EarningsInsight.builder()
                    .symbol(symbol)
                    .analyzedPeriods(castList(parsed.get("analyzedPeriods")))
                    .recurringThemes(castList(parsed.get("recurringThemes")))
                    .focusShifts(castList(parsed.get("focusShifts")))
                    .growthDrivers(castList(parsed.get("growthDrivers")))
                    .headwinds(castList(parsed.get("headwinds")))
                    .sentimentTrend((String) parsed.getOrDefault("sentimentTrend", "stable"))
                    .guidancePattern((String) parsed.getOrDefault("guidancePattern", ""))
                    .summary((String) parsed.getOrDefault("summary", ""))
                    .generatedAt(Instant.now())
                    .build();
        } catch (Exception ex) {
            log.error("Earnings insight analysis failed for {}: {}", symbol, ex.getMessage());
            return EarningsInsight.builder()
                    .symbol(symbol)
                    .error("Analysis failed: " + ex.getMessage())
                    .generatedAt(Instant.now())
                    .build();
        }
    }

    private String callClaude(String prompt, int maxTokens) throws Exception {
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicKey);
        headers.set("anthropic-version", "2023-06-01");

        ResponseEntity<Map> resp = restTemplate.exchange(
                ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        if (resp.getBody() == null) throw new RuntimeException("Empty Claude response");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        if (content == null || content.isEmpty()) throw new RuntimeException("No content in Claude response");

        return (String) content.get(0).get("text");
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object obj) {
        if (obj instanceof List<?> l) return (List<String>) l;
        return List.of();
    }
}
