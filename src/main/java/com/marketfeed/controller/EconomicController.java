package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.EconomicIndicator;
import com.marketfeed.service.FredService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/economic")
@RequiredArgsConstructor
@Tag(name = "Economic", description = "Macro indicators from FRED (Fed Funds, CPI, GDP, Treasury yields, etc.)")
public class EconomicController {

    private final FredService fredService;

    @GetMapping
    @Operation(summary = "All key macro indicators",
               description = "Returns latest values for: FEDFUNDS, DGS10, DGS2, CPIAUCSL, GDP, UNRATE, DTWEXBGS. Cached 1h.")
    public ResponseEntity<ApiResponse<Map<String, EconomicIndicator>>> getMacroSummary() {
        return ResponseEntity.ok(fredService.getMacroSummary());
    }

    @GetMapping("/{seriesId}")
    @Operation(summary = "Single FRED series",
               description = "Any valid FRED series ID, e.g. FEDFUNDS, DGS10, CPIAUCSL, UNRATE. Cached 1h.")
    public ResponseEntity<ApiResponse<EconomicIndicator>> getIndicator(@PathVariable String seriesId) {
        ApiResponse<EconomicIndicator> response = fredService.getLatest(seriesId);
        if (response.getError() != null) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/series")
    @Operation(summary = "List available series",
               description = "Returns all pre-defined FRED series IDs with names and units.")
    public ResponseEntity<Map<String, FredService.SeriesMeta>> listSeries() {
        return ResponseEntity.ok(FredService.SERIES);
    }
}
