package com.marketfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.QuoteUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceTickerService {

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "binance-poll"); t.setDaemon(true); return t; });

    @Value("${market-feed.binance.enabled:true}")
    private boolean enabled;

    @Value("${market-feed.binance.symbols:btcusdt,ethusdt,solusdt,xrpusdt,bnbusdt,dogeusdt,adausdt,avaxusdt,linkusdt,maticusdt}")
    private String symbolsConfig;

    @Value("${market-feed.binance.poll-seconds:5}")
    private int pollSeconds;

    private static final String BINANCE_TICKER_URL = "https://api.binance.com/api/v3/ticker/24hr?symbols=";

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("[Binance] REST feed disabled via config");
            return;
        }
        log.info("[Binance] Starting REST poll every {}s", pollSeconds);
        scheduler.scheduleAtFixedRate(this::poll, 0, pollSeconds, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            List<String> symbols = Arrays.stream(symbolsConfig.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());

            String symbolsJson = symbols.stream()
                    .collect(Collectors.joining("\",\"", "[\"", "\"]"));

            String url = BINANCE_TICKER_URL + java.net.URLEncoder.encode(symbolsJson, "UTF-8");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Binance] REST poll returned {}", response.statusCode());
                return;
            }

            JsonNode arr = objectMapper.readTree(response.body());
            if (!arr.isArray()) return;

            for (JsonNode node : arr) {
                String binanceSymbol = node.path("symbol").asText();
                if (binanceSymbol.isEmpty()) continue;

                double price         = node.path("lastPrice").asDouble();
                double open          = node.path("openPrice").asDouble();
                double high          = node.path("highPrice").asDouble();
                double low           = node.path("lowPrice").asDouble();
                double volume        = node.path("volume").asDouble();
                double changePercent = node.path("priceChangePercent").asDouble();
                double change        = price - open;

                if (price <= 0) continue;

                String symbol = normalizeBinanceSymbol(binanceSymbol);
                eventPublisher.publishEvent(new QuoteUpdatedEvent(
                        symbol, price, change, changePercent,
                        high, low, volume, "binance", Instant.now()
                ));
            }

            log.debug("[Binance] Polled {} symbols", arr.size());

        } catch (Exception e) {
            log.warn("[Binance] Poll failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private String normalizeBinanceSymbol(String binanceSymbol) {
        if (binanceSymbol.endsWith("USDT")) {
            return binanceSymbol.substring(0, binanceSymbol.length() - 4) + "-USD";
        }
        if (binanceSymbol.endsWith("USD")) {
            return binanceSymbol.substring(0, binanceSymbol.length() - 3) + "-USD";
        }
        return binanceSymbol;
    }
}
