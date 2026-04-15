package com.marketfeed.controller;

import com.marketfeed.model.Fundamentals;
import com.marketfeed.service.ScreenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/screener")
@RequiredArgsConstructor
@Tag(name = "Screener", description = "Stock screener — filter by valuation, growth, profitability")
public class ScreenerController {

    private final ScreenerService screenerService;

    @GetMapping
    @Operation(summary = "Screen stocks", description = "Filter the screener universe by P/E, market cap, margins, growth, beta, dividends.")
    public ResponseEntity<List<Fundamentals>> screen(
        @RequestParam(required = false) String  sector,
        @RequestParam(required = false) Double  minPE,
        @RequestParam(required = false) Double  maxPE,
        @RequestParam(required = false) Double  minMarketCapB,
        @RequestParam(required = false) Double  maxMarketCapB,
        @RequestParam(required = false) Double  minProfitMargin,
        @RequestParam(required = false) Double  minROE,
        @RequestParam(required = false) Double  minRevGrowth,
        @RequestParam(required = false) Double  maxBeta,
        @RequestParam(required = false) Double  minDivYield,
        @RequestParam(defaultValue = "marketCap") String sortBy,
        @RequestParam(defaultValue = "true")      boolean sortDesc
    ) {
        ScreenerService.ScreenerParams params = new ScreenerService.ScreenerParams(
            sector, minPE, maxPE, minMarketCapB, maxMarketCapB,
            minProfitMargin, minROE, minRevGrowth, maxBeta, minDivYield,
            sortBy, sortDesc
        );
        return ResponseEntity.ok(screenerService.screen(params));
    }

    @GetMapping("/sectors")
    @Operation(summary = "List available sectors")
    public ResponseEntity<List<String>> sectors() {
        return ResponseEntity.ok(screenerService.getSectors());
    }
}
