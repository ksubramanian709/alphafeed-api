package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResearchAnalysis {
    private String symbol;
    private String filingDate;
    private String filingUrl;
    private String secUrl;
    private List<String> keyInsights;
    private List<String> riskFactors;
    private List<String> opportunities;
    private String managementTone;
    private String guidance;
    private String summary;
    private String error;
    private Instant generatedAt;
}
