package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EarningsCalendarItem {
    private String symbol;
    private String name;
    private String reportDate;
    private String fiscalDateEnding;
    private Double estimate;
    private String currency;
}
