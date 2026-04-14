package com.marketfeed.controller;

import com.marketfeed.service.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/health")
@RequiredArgsConstructor
@Tag(name = "Health", description = "Service and upstream source health")
public class HealthController {

    private final QuoteService quoteService;

    @GetMapping
    @Operation(summary = "Health check",
               description = "Reports service status and availability of each upstream data source.")
    public ResponseEntity<Map<String, Object>> health() {
        List<QuoteService.SourceStatus> sources = quoteService.getSourceStatuses();
        boolean allUp = sources.stream().allMatch(QuoteService.SourceStatus::available);

        return ResponseEntity.ok(Map.of(
                "status",    allUp ? "UP" : "DEGRADED",
                "timestamp", Instant.now(),
                "sources",   sources
        ));
    }
}
