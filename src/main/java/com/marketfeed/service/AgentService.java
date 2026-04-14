package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final QuoteService quoteService;
    private final FredService fredService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${market-feed.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${market-feed.anthropic.model:claude-sonnet-4-5}")
    private String model;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    // Common commodity keywords → futures symbols
    private static final Map<String, String> KEYWORD_SYMBOLS = new LinkedHashMap<>();
    static {
        KEYWORD_SYMBOLS.put("corn",         "ZC=F");
        KEYWORD_SYMBOLS.put("wheat",        "ZW=F");
        KEYWORD_SYMBOLS.put("soybean",      "ZS=F");
        KEYWORD_SYMBOLS.put("soybeans",     "ZS=F");
        KEYWORD_SYMBOLS.put("crude",        "CL=F");
        KEYWORD_SYMBOLS.put("oil",          "CL=F");
        KEYWORD_SYMBOLS.put("wti",          "CL=F");
        KEYWORD_SYMBOLS.put("brent",        "BZ=F");
        KEYWORD_SYMBOLS.put("natural gas",  "NG=F");
        KEYWORD_SYMBOLS.put("natgas",       "NG=F");
        KEYWORD_SYMBOLS.put("gold",         "GC=F");
        KEYWORD_SYMBOLS.put("silver",       "SI=F");
        KEYWORD_SYMBOLS.put("copper",       "HG=F");
        KEYWORD_SYMBOLS.put("vix",          "^VIX");
        KEYWORD_SYMBOLS.put("s&p",          "^GSPC");
        KEYWORD_SYMBOLS.put("sp500",        "^GSPC");
        KEYWORD_SYMBOLS.put("nasdaq",       "^IXIC");
    }

    public AgentResponse query(AgentRequest request) {
        if (anthropicKey == null || anthropicKey.isBlank()) {
            return AgentResponse.builder()
                    .error("ANTHROPIC_API_KEY not configured")
                    .generatedAt(Instant.now())
                    .build();
        }

        String question = request.getQuestion();
        List<String> symbols = resolveSymbols(request);

        // Gather live market context
        Map<String, Object> context = buildMarketContext(symbols);

        // Build the prompt
        String prompt = buildPrompt(question, symbols, context);

        // Call Claude
        try {
            String answer = callClaude(prompt);
            return AgentResponse.builder()
                    .answer(answer)
                    .model(model)
                    .symbolsAnalyzed(symbols)
                    .marketContext(context)
                    .generatedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            return AgentResponse.builder()
                    .error("Failed to get answer: " + e.getMessage())
                    .marketContext(context)
                    .symbolsAnalyzed(symbols)
                    .generatedAt(Instant.now())
                    .build();
        }
    }

    // ---------- symbol resolution ----------

    private List<String> resolveSymbols(AgentRequest request) {
        if (request.getSymbols() != null && !request.getSymbols().isEmpty()) {
            return request.getSymbols().stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());
        }
        // Infer from question text
        String q = request.getQuestion().toLowerCase();
        List<String> inferred = new ArrayList<>();
        KEYWORD_SYMBOLS.forEach((keyword, symbol) -> {
            if (q.contains(keyword) && !inferred.contains(symbol)) {
                inferred.add(symbol);
            }
        });
        // Default to a broad market snapshot if nothing inferred
        if (inferred.isEmpty()) {
            inferred.addAll(List.of("^GSPC", "^VIX", "CL=F", "GC=F"));
        }
        return inferred;
    }

    // ---------- context building ----------

    private Map<String, Object> buildMarketContext(List<String> symbols) {
        Map<String, Object> context = new LinkedHashMap<>();

        // Live quotes
        Map<String, Object> quotes = new LinkedHashMap<>();
        for (String symbol : symbols) {
            ApiResponse<Quote> r = quoteService.getQuote(symbol);
            if (r.getData() != null) {
                Quote q = r.getData();
                quotes.put(symbol, Map.of(
                        "price",         q.getPrice(),
                        "change",        q.getChange(),
                        "changePercent", q.getChangePercent(),
                        "high",          q.getHigh(),
                        "low",           q.getLow(),
                        "volume",        q.getVolume(),
                        "currency",      q.getCurrency(),
                        "assetType",     q.getAssetType(),
                        "source",        r.getSource()
                ));
            }
        }
        context.put("quotes", quotes);

        // 7-day history for each symbol
        Map<String, Object> histories = new LinkedHashMap<>();
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(10); // slightly more to account for weekends
        for (String symbol : symbols) {
            ApiResponse<List<OhlcvBar>> r = quoteService.getHistory(symbol, from, to);
            if (r.getData() != null && !r.getData().isEmpty()) {
                List<Map<String, Object>> bars = r.getData().stream()
                        .map(b -> Map.<String, Object>of(
                                "date",   b.getDate().toString(),
                                "open",   b.getOpen(),
                                "high",   b.getHigh(),
                                "low",    b.getLow(),
                                "close",  b.getClose(),
                                "volume", b.getVolume()
                        ))
                        .collect(Collectors.toList());
                histories.put(symbol, bars);
            }
        }
        context.put("recentHistory", histories);

        // Macro context from FRED (if configured)
        ApiResponse<Map<String, EconomicIndicator>> macro = fredService.getMacroSummary();
        if (macro.getData() != null && !macro.getData().isEmpty()) {
            Map<String, Object> macroData = new LinkedHashMap<>();
            macro.getData().forEach((id, ind) ->
                    macroData.put(ind.getName(), Map.of(
                            "value", ind.getValue(),
                            "unit",  ind.getUnit(),
                            "date",  ind.getDate().toString()
                    ))
            );
            context.put("macroIndicators", macroData);
        }

        context.put("dataAsOf", Instant.now().toString());
        return context;
    }

    // ---------- prompt construction ----------

    private String buildPrompt(String question, List<String> symbols, Map<String, Object> context) {
        try {
            String contextJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(context);

            return """
                    You are a market analyst assistant with access to real-time market data.

                    The following live data was pulled from a multi-source market data platform
                    (sources: Yahoo Finance, Alpha Vantage, FRED) at the time of this request:

                    %s

                    Based ONLY on the data above, answer the following question concisely and precisely.
                    If the data is insufficient to fully answer, say what you can determine and what
                    additional data would be needed. Do not speculate beyond what the data shows.

                    Question: %s
                    """.formatted(contextJson, question);
        } catch (Exception e) {
            return "Market data context unavailable.\n\nQuestion: " + question;
        }
    }

    // ---------- Claude API call ----------

    private String callClaude(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicKey);
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<ClaudeResponse> response = restTemplate.exchange(
                ANTHROPIC_URL, HttpMethod.POST, entity, ClaudeResponse.class);

        if (response.getBody() == null || response.getBody().getContent() == null
                || response.getBody().getContent().isEmpty()) {
            throw new RuntimeException("Empty response from Claude");
        }

        return response.getBody().getContent().get(0).getText();
    }

    // ---------- response POJO ----------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClaudeResponse {
        private List<ContentBlock> content;
        private String model;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ContentBlock {
            private String type;
            private String text;
        }
    }
}
