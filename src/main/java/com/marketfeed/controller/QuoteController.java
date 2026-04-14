package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.OhlcvBar;
import com.marketfeed.model.Quote;
import com.marketfeed.service.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Quotes", description = "Real-time quotes and historical OHLCV data")
public class QuoteController {

    private final QuoteService quoteService;

    @GetMapping("/quote/{symbol}")
    @Operation(summary = "Get latest quote for a symbol",
               description = "Fetches real-time (delayed) quote. Falls back across sources automatically. Cached 60s.")
    public ResponseEntity<ApiResponse<Quote>> getQuote(
            @PathVariable @Parameter(description = "Ticker symbol, e.g. AAPL, CL=F, GC=F, ^VIX") String symbol) {

        ApiResponse<Quote> response = quoteService.getQuote(symbol);
        if (response.getError() != null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/chart/{symbol}")
    @Operation(summary = "Chart data with interval and range",
               description = "interval: 1m,5m,15m,60m,1d,1wk,1mo — range: 1d,5d,1mo,3mo,6mo,1y,5y")
    public ResponseEntity<ApiResponse<List<OhlcvBar>>> getChart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(defaultValue = "1mo") String range) {
        ApiResponse<List<OhlcvBar>> response = quoteService.getChart(symbol, interval, range);
        if (response.getError() != null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/{symbol}")
    @Operation(summary = "Get OHLCV history for a symbol",
               description = "Returns daily OHLCV bars for the given date range. Cached 6h.")
    public ResponseEntity<ApiResponse<List<OhlcvBar>>> getHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now().minusDays(30)}")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Start date (YYYY-MM-DD), default 30 days ago") LocalDate from,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "End date (YYYY-MM-DD), default today") LocalDate to) {

        ApiResponse<List<OhlcvBar>> response = quoteService.getHistory(symbol, from, to);
        if (response.getError() != null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
