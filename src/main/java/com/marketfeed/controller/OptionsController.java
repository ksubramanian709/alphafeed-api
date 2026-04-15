package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.OptionsChain;
import com.marketfeed.service.OptionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/options")
@RequiredArgsConstructor
@Tag(name = "Options", description = "Options chain data via Yahoo Finance")
public class OptionsController {

    private final OptionsService optionsService;

    @GetMapping("/{symbol}")
    @Operation(
        summary = "Fetch options chain for a symbol",
        description = """
            Returns calls and puts for the given symbol with full strike ladder,
            bid/ask, IV, volume, open interest, and ITM flag.

            Pass ?expiration=<epoch_seconds> to select a specific expiration date.
            Omit for the nearest available expiration.

            Examples: AAPL, TSLA, SPY, QQQ, ^SPX, ^VIX
            """
    )
    public ResponseEntity<ApiResponse<OptionsChain>> getOptions(
            @PathVariable String symbol,
            @RequestParam(required = false) Long expiration) {

        ApiResponse<OptionsChain> response = optionsService.getOptionsChain(symbol, expiration);

        if (response.getData() == null) {
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
