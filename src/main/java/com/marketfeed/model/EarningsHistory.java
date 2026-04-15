package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EarningsHistory {
    private String symbol;
    private List<QuarterlyEarning> quarterlyEarnings;
    private String error;
}
