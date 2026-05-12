package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class EarningsInsight {
    private String symbol;
    private List<String> analyzedPeriods;
    private List<String> recurringThemes;
    private List<String> focusShifts;
    private List<String> growthDrivers;
    private List<String> headwinds;
    private String sentimentTrend;
    private String guidancePattern;
    private String summary;
    private String error;
    private Instant generatedAt;
}
