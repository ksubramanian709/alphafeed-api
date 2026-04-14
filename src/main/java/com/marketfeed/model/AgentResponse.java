package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentResponse {
    private String answer;
    private String model;
    private List<String> symbolsAnalyzed;
    private Map<String, Object> marketContext;  // the live data fed to Claude
    private Instant generatedAt;
    private String error;
}
