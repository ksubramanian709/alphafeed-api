package com.marketfeed.service;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.OhlcvBar;
import com.marketfeed.model.Quote;
import com.marketfeed.source.MarketDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final List<MarketDataSource> sources; // Spring injects all MarketDataSource beans in order

    /**
     * Fetches a quote by trying each source in priority order (AlphaVantage → Yahoo).
     * Result is cached for 60 seconds via CacheConfig.
     */
    @Cacheable(value = "quotes", key = "#symbol.toUpperCase()")
    public ApiResponse<Quote> getQuote(String symbol) {
        for (MarketDataSource source : sources) {
            if (!source.isAvailable()) {
                log.debug("Source {} unavailable, skipping", source.getName());
                continue;
            }
            Optional<Quote> quote = source.getQuote(symbol.toUpperCase());
            if (quote.isPresent()) {
                log.debug("Fetched {} from {}", symbol, source.getName());
                return ApiResponse.success(quote.get(), source.getName());
            }
        }
        log.warn("All sources failed for symbol: {}", symbol);
        return ApiResponse.error("No data available for symbol: " + symbol);
    }

    /**
     * Fetches OHLCV history. Cached for 6 hours (see CacheConfig).
     */
    @Cacheable(value = "history", key = "#symbol.toUpperCase() + '_' + #from + '_' + #to")
    public ApiResponse<List<OhlcvBar>> getHistory(String symbol, LocalDate from, LocalDate to) {
        for (MarketDataSource source : sources) {
            if (!source.isAvailable()) continue;
            List<OhlcvBar> bars = source.getHistory(symbol.toUpperCase(), from, to);
            if (!bars.isEmpty()) {
                return ApiResponse.success(bars, source.getName());
            }
        }
        return ApiResponse.error("No history available for symbol: " + symbol);
    }

    /**
     * Source health status — used by /v1/health endpoint.
     */
    public List<SourceStatus> getSourceStatuses() {
        return sources.stream()
                .map(s -> new SourceStatus(s.getName(), s.isAvailable()))
                .toList();
    }

    public record SourceStatus(String name, boolean available) {}
}
