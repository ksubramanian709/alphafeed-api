package com.marketfeed.controller;

import com.marketfeed.model.Fundamentals;
import com.marketfeed.service.FundamentalsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/fundamentals")
@RequiredArgsConstructor
@Tag(name = "Fundamentals", description = "Company fundamentals — valuation, profitability, growth")
public class FundamentalsController {

    private final FundamentalsService fundamentalsService;

    @GetMapping("/{symbol}")
    @Operation(
        summary = "Get company fundamentals",
        description = "Returns P/E, forward P/E, EPS, revenue, margins, growth, analyst target, beta, and more from Alpha Vantage OVERVIEW."
    )
    public ResponseEntity<Fundamentals> getFundamentals(@PathVariable String symbol) {
        Fundamentals result = fundamentalsService.getFundamentals(symbol);
        if (result.getError() != null) {
            return ResponseEntity.status(503).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
