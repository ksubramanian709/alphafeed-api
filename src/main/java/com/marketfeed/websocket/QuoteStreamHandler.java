package com.marketfeed.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.Quote;
import com.marketfeed.service.QuoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for live quote streaming.
 *
 * Protocol:
 *   Connect to ws://host/v1/stream/quotes
 *   Send: {"action":"subscribe","symbols":["AAPL","CL=F","GC=F"]}
 *   Send: {"action":"unsubscribe","symbols":["AAPL"]}
 *   Receive: {"symbol":"AAPL","price":185.92,"change":1.23,...,"source":"yahoo_finance"}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteStreamHandler extends TextWebSocketHandler {

    private final QuoteService quoteService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // session → set of subscribed symbols
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
        log.info("WebSocket connected: {}", session.getId());
        sendMessage(session, Map.of("type", "connected", "message",
                "Send {\"action\":\"subscribe\",\"symbols\":[\"AAPL\",\"CL=F\"]}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String action = node.path("action").asText();
        JsonNode symbolsNode = node.path("symbols");

        if (symbolsNode.isArray()) {
            Set<String> sessionSymbols = subscriptions.get(session.getId());
            List<String> symbols = new ArrayList<>();
            symbolsNode.forEach(s -> symbols.add(s.asText().toUpperCase()));

            if ("subscribe".equalsIgnoreCase(action)) {
                sessionSymbols.addAll(symbols);
                log.debug("Session {} subscribed to {}", session.getId(), symbols);
                sendMessage(session, Map.of("type", "subscribed", "symbols", symbols));
            } else if ("unsubscribe".equalsIgnoreCase(action)) {
                sessionSymbols.removeAll(symbols);
                sendMessage(session, Map.of("type", "unsubscribed", "symbols", symbols));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        subscriptions.remove(session.getId());
        log.info("WebSocket disconnected: {} ({})", session.getId(), status);
    }

    /**
     * Every N seconds, push fresh quotes to all subscribed sessions.
     * Interval is configured in application.yml → market-feed.stream.interval-seconds
     */
    @Scheduled(fixedDelayString = "${market-feed.stream.interval-seconds:15}000")
    public void broadcastQuotes() {
        subscriptions.forEach((sessionId, symbols) -> {
            if (symbols.isEmpty()) return;
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen()) return;

            symbols.forEach(symbol -> {
                try {
                    ApiResponse<Quote> response = quoteService.getQuote(symbol);
                    if (response.getData() != null) {
                        Map<String, Object> payload = quoteToMap(response);
                        sendMessage(session, payload);
                    }
                } catch (Exception e) {
                    log.warn("Failed to push quote for {} to {}: {}", symbol, sessionId, e.getMessage());
                }
            });
        });
    }

    private Map<String, Object> quoteToMap(ApiResponse<Quote> response) {
        Quote q = response.getData();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "quote");
        map.put("symbol", q.getSymbol());
        map.put("name", q.getName());
        map.put("price", q.getPrice());
        map.put("change", q.getChange());
        map.put("changePercent", q.getChangePercent());
        map.put("open", q.getOpen());
        map.put("high", q.getHigh());
        map.put("low", q.getLow());
        map.put("volume", q.getVolume());
        map.put("currency", q.getCurrency());
        map.put("assetType", q.getAssetType());
        map.put("source", response.getSource());
        map.put("timestamp", q.getTimestamp());
        return map;
    }

    private void sendMessage(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send WebSocket message: {}", e.getMessage());
        }
    }
}
