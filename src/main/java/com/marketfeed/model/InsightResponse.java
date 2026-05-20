package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class InsightResponse {
    private String symbol;
    private String sentiment;
    private String summary;
    private List<String> bullPoints;
    private List<String> bearPoints;
    private String keyRisk;
    private String catalyst;
    private Instant generatedAt;
    private String error;
}
