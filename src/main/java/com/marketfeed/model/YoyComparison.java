package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class YoyComparison {
    private String symbol;
    private String olderFilingDate;
    private String newerFilingDate;
    private List<String> newRisks;
    private List<String> removedRisks;
    private List<String> keyChanges;
    private String toneChange;
    private String summary;
    private String error;
    private Instant generatedAt;
}
