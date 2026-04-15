package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuarterlyEarning {
    private String fiscalDateEnding;
    private String reportedDate;
    private Double reportedEps;
    private Double estimatedEps;
    private Double surprise;
    private Double surprisePercentage;
}
