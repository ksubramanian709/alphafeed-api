package com.marketfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.OhlcvBar;
import com.marketfeed.model.SentimentResult;
import com.marketfeed.model.SentimentResult.PricePoint;
import com.marketfeed.model.SentimentResult.TodaySentiment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentService {

    private final QuoteService quoteService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${market-feed.reddit.client-id:}")
    private String redditClientId;

    @Value("${market-feed.reddit.client-secret:}")
    private String redditClientSecret;

    @Value("${market-feed.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${market-feed.anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static final String REDDIT_TOKEN_URL  = "https://www.reddit.com/api/v1/access_token";
    private static final String REDDIT_WSB_URL    = "https://oauth.reddit.com/r/wallstreetbets/hot";
    private static final String ANTHROPIC_URL     = "https://api.anthropic.com/v1/messages";
    private static final String USER_AGENT        = "market-feed-sentiment/1.0";
    // Matches $AAPL or standalone AAPL (1-5 uppercase letters, word-bounded)
    private static final Pattern TICKER_PATTERN   = Pattern.compile("(?:\\$|\\b)([A-Z]{1,5})\\b");
    private static final int     MIN_MENTIONS      = 3;
    private static final int     BATCH_SIZE        = 30;
    private static final int     HISTORY_DAYS      = 30;

    @Cacheable(value = "sentiment", key = "#symbol.toUpperCase()")
    public SentimentResult getSentiment(String symbol) {
        String sym = symbol.toUpperCase();

        List<PricePoint> priceHistory = fetchPriceHistory(sym);

        if ((redditClientId == null || redditClientId.isBlank())
                || (redditClientSecret == null || redditClientSecret.isBlank())) {
            return SentimentResult.builder()
                    .symbol(sym)
                    .priceHistory(priceHistory)
                    .error("Reddit API credentials not configured (REDDIT_CLIENT_ID / REDDIT_CLIENT_SECRET)")
                    .fetchedAt(Instant.now())
                    .build();
        }

        if (anthropicKey == null || anthropicKey.isBlank()) {
            return SentimentResult.builder()
                    .symbol(sym)
                    .priceHistory(priceHistory)
                    .error("ANTHROPIC_API_KEY not configured")
                    .fetchedAt(Instant.now())
                    .build();
        }

        try {
            String token = fetchRedditToken();
            List<String> posts = fetchWsbPosts(token);
            List<String> matching = filterByTicker(posts, sym);

            if (matching.size() < MIN_MENTIONS) {
                return SentimentResult.builder()
                        .symbol(sym)
                        .today(TodaySentiment.builder()
                                .date(LocalDate.now())
                                .score(0.0)
                                .mentionCount(matching.size())
                                .label("neutral")
                                .build())
                        .priceHistory(priceHistory)
                        .fetchedAt(Instant.now())
                        .build();
            }

            double score = scoreWithClaude(sym, matching);
            String label = score > 0.15 ? "bullish" : score < -0.15 ? "bearish" : "neutral";

            return SentimentResult.builder()
                    .symbol(sym)
                    .today(TodaySentiment.builder()
                            .date(LocalDate.now())
                            .score(Math.round(score * 100.0) / 100.0)
                            .mentionCount(matching.size())
                            .label(label)
                            .build())
                    .priceHistory(priceHistory)
                    .fetchedAt(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("Sentiment fetch failed for {}: {}", sym, e.getMessage(), e);
            return SentimentResult.builder()
                    .symbol(sym)
                    .priceHistory(priceHistory)
                    .error("Sentiment analysis failed: " + e.getMessage())
                    .fetchedAt(Instant.now())
                    .build();
        }
    }

    // ─── Reddit ──────────────────────────────────────────────────────────────────

    private String fetchRedditToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("User-Agent", USER_AGENT);
        headers.setBasicAuth(redditClientId, redditClientSecret);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        ResponseEntity<Map> resp = restTemplate.exchange(
                REDDIT_TOKEN_URL, HttpMethod.POST,
                new HttpEntity<>(form, headers), Map.class);

        if (resp.getBody() == null) throw new RuntimeException("Empty Reddit token response");
        String token = (String) resp.getBody().get("access_token");
        if (token == null) throw new RuntimeException("No access_token in Reddit response");
        return token;
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchWsbPosts(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("User-Agent", USER_AGENT);

        ResponseEntity<Map> resp = restTemplate.exchange(
                REDDIT_WSB_URL + "?limit=100&t=day",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        if (resp.getBody() == null) return List.of();

        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        if (data == null) return List.of();
        List<Map<String, Object>> children = (List<Map<String, Object>>) data.get("children");
        if (children == null) return List.of();

        return children.stream()
                .map(child -> {
                    Map<String, Object> post = (Map<String, Object>) child.get("data");
                    if (post == null) return null;
                    String title = (String) post.getOrDefault("title", "");
                    // Include upvote ratio in output for context but only keep title for Claude
                    return title;
                })
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());
    }

    private List<String> filterByTicker(List<String> titles, String symbol) {
        return titles.stream()
                .filter(title -> {
                    Matcher m = TICKER_PATTERN.matcher(title);
                    while (m.find()) {
                        if (symbol.equals(m.group(1))) return true;
                    }
                    return false;
                })
                .limit(BATCH_SIZE)
                .collect(Collectors.toList());
    }

    // ─── Claude scoring ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private double scoreWithClaude(String symbol, List<String> titles) throws Exception {
        String titlesBlock = titles.stream()
                .map(t -> "- " + t)
                .collect(Collectors.joining("\n"));

        String prompt = """
                Below are %d Reddit posts from r/wallstreetbets that mention %s.
                Score the overall retail investor sentiment for %s from -1.0 (very bearish) to +1.0 (very bullish).

                Posts:
                %s

                Rules:
                - Consider tone, context, and WSB culture (irony, "to the moon", "puts", "calls", "bag holder", etc.)
                - Return ONLY a JSON object with one key: {"score": <number between -1.0 and 1.0>}
                - No explanation, no other text
                """.formatted(titles.size(), symbol, symbol, titlesBlock);

        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 50);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicKey);
        headers.set("anthropic-version", "2023-06-01");

        ResponseEntity<Map> resp = restTemplate.exchange(
                ANTHROPIC_URL, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        if (resp.getBody() == null) throw new RuntimeException("Empty Claude response");
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        if (content == null || content.isEmpty()) throw new RuntimeException("No content in Claude response");

        String raw = (String) content.get(0).get("text");
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start == -1 || end == -1) throw new RuntimeException("No JSON object in Claude response: " + raw);

        Map<String, Object> parsed = objectMapper.readValue(raw.substring(start, end + 1), Map.class);
        Object scoreVal = parsed.get("score");
        if (scoreVal instanceof Number n) {
            double score = n.doubleValue();
            return Math.max(-1.0, Math.min(1.0, score));
        }
        throw new RuntimeException("Unexpected score value: " + scoreVal);
    }

    // ─── Price history ────────────────────────────────────────────────────────────

    private List<PricePoint> fetchPriceHistory(String symbol) {
        try {
            LocalDate to   = LocalDate.now();
            LocalDate from = to.minusDays(HISTORY_DAYS);
            ApiResponse<List<OhlcvBar>> resp = quoteService.getHistory(symbol, from, to);
            if (resp.getData() == null || resp.getData().isEmpty()) return List.of();

            List<OhlcvBar> bars = resp.getData();
            List<PricePoint> points = new ArrayList<>();
            for (int i = 0; i < bars.size(); i++) {
                OhlcvBar bar = bars.get(i);
                double prev  = i > 0 ? bars.get(i - 1).getClose() : bar.getClose();
                double change = prev != 0 ? ((bar.getClose() - prev) / prev) * 100.0 : 0.0;
                points.add(PricePoint.builder()
                        .date(bar.getDate())
                        .close(bar.getClose())
                        .changePercent(Math.round(change * 100.0) / 100.0)
                        .build());
            }
            return points;
        } catch (Exception e) {
            log.warn("Price history fetch failed for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }
}
