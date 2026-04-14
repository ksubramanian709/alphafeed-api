package com.marketfeed.controller;

import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.WasdeReport;
import com.marketfeed.service.UsdaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/agriculture")
@RequiredArgsConstructor
@Tag(name = "Agriculture", description = "USDA world grain supply & demand data (Corn, Wheat, Soybeans)")
public class AgricultureController {

    private final UsdaService usdaService;

    @GetMapping("/wasde")
    @Operation(summary = "Grain commodity price series",
               description = "Returns latest monthly prices for corn, wheat, and soybeans (Alpha Vantage). " +
                             "Cached 12h — aligned with WASDE monthly cadence. " +
                             "S&D fundamentals (production/consumption/exports) will be added via USDA FAS PSD when available.")
    public ResponseEntity<ApiResponse<List<WasdeReport>>> getWasde() {
        ApiResponse<List<WasdeReport>> response = usdaService.getGrainPrices();
        if (response.getError() != null) {
            return ResponseEntity.status(502).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
