package com.marketfeed.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Fuzzy symbol search — type a company name, get back the ticker")
public class SearchController {

    private final RestTemplate restTemplate;

    private static final String YAHOO_SEARCH_URL =
            "https://query1.finance.yahoo.com/v1/finance/search" +
            "?q=%s&quotesCount=6&newsCount=0&enableFuzzyQuery=true&enableCb=false";

    @GetMapping
    @Operation(summary = "Resolve a company name or partial ticker to a symbol",
               description = "Supports fuzzy matching — 'plantir', 'apple', 'gold futures' all work.")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String q) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "q is required"));
        }
        try {
            String url = String.format(YAHOO_SEARCH_URL, q.trim());

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<YahooSearchResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, YahooSearchResponse.class);

            if (response.getBody() == null
                    || response.getBody().getFinance() == null
                    || response.getBody().getFinance().getResult() == null
                    || response.getBody().getFinance().getResult().isEmpty()) {
                return ResponseEntity.ok(Map.of("results", Collections.emptyList()));
            }

            List<SearchResult> results = response.getBody().getFinance().getResult().stream()
                    .filter(r -> r.getSymbol() != null)
                    .map(r -> new SearchResult(
                            r.getSymbol(),
                            r.getLongname() != null ? r.getLongname()
                                    : r.getShortname() != null ? r.getShortname() : r.getSymbol(),
                            r.getExchange() != null ? r.getExchange() : "",
                            r.getTypeDisp() != null ? r.getTypeDisp() : r.getQuoteType()
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of("results", results));

        } catch (Exception e) {
            log.warn("Symbol search failed for '{}': {}", q, e.getMessage());
            return ResponseEntity.ok(Map.of("results", Collections.emptyList(), "error", e.getMessage()));
        }
    }

    // ---- response record sent to frontend ----
    public record SearchResult(String symbol, String name, String exchange, String type) {}

    // ---- Yahoo Finance search response POJOs ----

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class YahooSearchResponse {
        private Finance finance;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Finance {
            private List<Quote> result;

            @Data @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Quote {
                private String symbol;
                private String longname;
                private String shortname;
                private String exchange;
                private String quoteType;
                @JsonProperty("typeDisp")
                private String typeDisp;
            }
        }
    }
}
