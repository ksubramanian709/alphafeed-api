package com.marketfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.*;
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
public class FilingAnalysisService {

    private final SecEdgarService edgarService;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    objectMapper;

    @Value("${market-feed.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${market-feed.anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    // ─── Latest 10-K analysis ────────────────────────────────────────────────────

    @Cacheable(value = "research-analysis", key = "#symbol")
    public ResearchAnalysis analyzeLatest(String symbol) {
        List<FilingInfo> filings = edgarService.getFilings(symbol, "10-K", 1);
        if (filings.isEmpty()) {
            return ResearchAnalysis.builder()
                    .symbol(symbol).error("No 10-K filing found on SEC EDGAR")
                    .generatedAt(Instant.now()).build();
        }
        FilingInfo filing = filings.get(0);
        Map<String, String> sections = edgarService.fetchAndParseSections(filing);
        if (sections.isEmpty()) {
            return ResearchAnalysis.builder()
                    .symbol(symbol).error("Could not extract filing content")
                    .filingDate(filing.getFilingDate()).secUrl(filing.getSecUrl())
                    .generatedAt(Instant.now()).build();
        }
        return analyzeWithClaude(symbol, filing, sections);
    }

    // ─── Year-over-year comparison ───────────────────────────────────────────────

    @Cacheable(value = "research-compare", key = "#symbol")
    public YoyComparison compareYoY(String symbol) {
        List<FilingInfo> filings = edgarService.getFilings(symbol, "10-K", 2);
        if (filings.size() < 2) {
            return YoyComparison.builder()
                    .symbol(symbol).error("Need at least 2 annual filings to compare")
                    .generatedAt(Instant.now()).build();
        }
        FilingInfo older = filings.get(1);
        FilingInfo newer = filings.get(0);
        Map<String, String> sec1 = edgarService.fetchAndParseSections(older);
        Map<String, String> sec2 = edgarService.fetchAndParseSections(newer);
        return compareWithClaude(symbol, older, newer, sec1, sec2);
    }

    // ─── Claude: single filing analysis ──────────────────────────────────────────

    private ResearchAnalysis analyzeWithClaude(String symbol, FilingInfo filing, Map<String, String> sections) {
        StringBuilder ctx = new StringBuilder();
        sections.forEach((name, content) -> {
            if (!content.isBlank()) {
                ctx.append("### ").append(name).append("\n")
                   .append(content, 0, Math.min(content.length(), 5000))
                   .append("\n\n");
            }
        });

        String prompt = """
                You are a senior equity analyst reviewing a 10-K SEC filing for %s (filed %s).

                Filing sections:
                %s

                Return ONLY a valid JSON object, no other text:
                {
                  "summary": "2-3 sentence executive summary focused on what investors need to know",
                  "keyInsights": ["5 specific data-backed insights from MD&A or business section"],
                  "riskFactors": ["3 most significant risks from the Risk Factors section"],
                  "opportunities": ["3 key growth opportunities mentioned"],
                  "managementTone": "bullish|cautious|neutral|bearish",
                  "guidance": "One sentence on forward guidance with specific numbers if available"
                }

                Rules: be specific and cite actual figures, percentages, or market dynamics where present.
                managementTone must be exactly one of the four values.
                """.formatted(symbol, filing.getFilingDate(), ctx);

        try {
            String raw = callClaude(prompt, 1800);
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s == -1 || e == -1) throw new RuntimeException("No JSON in Claude response");

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw.substring(s, e + 1), Map.class);

            return ResearchAnalysis.builder()
                    .symbol(symbol)
                    .filingDate(filing.getFilingDate())
                    .secUrl(filing.getSecUrl())
                    .summary((String) parsed.getOrDefault("summary", ""))
                    .keyInsights(castList(parsed.get("keyInsights")))
                    .riskFactors(castList(parsed.get("riskFactors")))
                    .opportunities(castList(parsed.get("opportunities")))
                    .managementTone((String) parsed.getOrDefault("managementTone", "neutral"))
                    .guidance((String) parsed.getOrDefault("guidance", ""))
                    .generatedAt(Instant.now())
                    .build();
        } catch (Exception ex) {
            log.error("Filing analysis failed for {}: {}", symbol, ex.getMessage());
            return ResearchAnalysis.builder()
                    .symbol(symbol).filingDate(filing.getFilingDate()).secUrl(filing.getSecUrl())
                    .error("Analysis failed: " + ex.getMessage())
                    .generatedAt(Instant.now()).build();
        }
    }

    // ─── Claude: year-over-year comparison ───────────────────────────────────────

    private YoyComparison compareWithClaude(String symbol, FilingInfo older, FilingInfo newer,
                                             Map<String, String> sec1, Map<String, String> sec2) {
        String r1   = trunc(sec1.getOrDefault("Risk Factors", ""), 4000);
        String r2   = trunc(sec2.getOrDefault("Risk Factors", ""), 4000);
        String mda1 = trunc(sec1.getOrDefault("Management Discussion", ""), 3000);
        String mda2 = trunc(sec2.getOrDefault("Management Discussion", ""), 3000);

        String prompt = """
                Compare two 10-K filings for %s.

                OLDER FILING (%s):
                Risk Factors: %s
                MD&A: %s

                NEWER FILING (%s):
                Risk Factors: %s
                MD&A: %s

                Return ONLY a valid JSON object, no other text:
                {
                  "newRisks": ["risks that appeared in newer filing but not in older (2-4 items)"],
                  "removedRisks": ["risks from older filing no longer in newer (2-4 items)"],
                  "keyChanges": ["4-5 most significant shifts in language, emphasis, or strategy"],
                  "toneChange": "more bullish|more bearish|similar",
                  "summary": "2-3 sentence narrative of what changed and what it means for investors"
                }

                Be specific — mention actual topics, not generic descriptions.
                toneChange must be exactly one of the three values.
                """.formatted(
                        symbol,
                        older.getFilingDate(), r1, mda1,
                        newer.getFilingDate(), r2, mda2);

        try {
            String raw = callClaude(prompt, 1500);
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s == -1 || e == -1) throw new RuntimeException("No JSON in Claude response");

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw.substring(s, e + 1), Map.class);

            return YoyComparison.builder()
                    .symbol(symbol)
                    .olderFilingDate(older.getFilingDate())
                    .newerFilingDate(newer.getFilingDate())
                    .newRisks(castList(parsed.get("newRisks")))
                    .removedRisks(castList(parsed.get("removedRisks")))
                    .keyChanges(castList(parsed.get("keyChanges")))
                    .toneChange((String) parsed.getOrDefault("toneChange", "similar"))
                    .summary((String) parsed.getOrDefault("summary", ""))
                    .generatedAt(Instant.now())
                    .build();
        } catch (Exception ex) {
            log.error("YoY comparison failed for {}: {}", symbol, ex.getMessage());
            return YoyComparison.builder()
                    .symbol(symbol)
                    .olderFilingDate(older.getFilingDate())
                    .newerFilingDate(newer.getFilingDate())
                    .error("Comparison failed: " + ex.getMessage())
                    .generatedAt(Instant.now()).build();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

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

    private String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
