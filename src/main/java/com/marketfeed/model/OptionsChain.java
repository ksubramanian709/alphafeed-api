package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OptionsChain {
    private String               underlyingSymbol;
    private double               underlyingPrice;
    private long                 expirationDate;       // epoch seconds for this slice
    private List<Long>           allExpirationDates;   // all available expirations
    private List<Double>         strikes;
    private List<OptionsContract> calls;
    private List<OptionsContract> puts;
}
