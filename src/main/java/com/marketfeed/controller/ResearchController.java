package com.marketfeed.controller;

import com.marketfeed.model.*;
import com.marketfeed.service.FilingAnalysisService;
import com.marketfeed.service.SecEdgarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/research")
@RequiredArgsConstructor
public class ResearchController {

    private final SecEdgarService       edgarService;
    private final FilingAnalysisService analysisService;

    @GetMapping("/{symbol}/filings")
    public ApiResponse<List<FilingInfo>> filings(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "10-K") String form,
            @RequestParam(defaultValue = "5") int limit) {
        List<FilingInfo> data = edgarService.getFilings(symbol.toUpperCase(), form, Math.min(limit, 10));
        return ApiResponse.<List<FilingInfo>>builder().data(data).build();
    }

    @GetMapping("/{symbol}/analysis")
    public ApiResponse<ResearchAnalysis> analysis(@PathVariable String symbol) {
        ResearchAnalysis data = analysisService.analyzeLatest(symbol.toUpperCase());
        return ApiResponse.<ResearchAnalysis>builder().data(data).build();
    }

    @GetMapping("/{symbol}/compare")
    public ApiResponse<YoyComparison> compare(@PathVariable String symbol) {
        YoyComparison data = analysisService.compareYoY(symbol.toUpperCase());
        return ApiResponse.<YoyComparison>builder().data(data).build();
    }
}
