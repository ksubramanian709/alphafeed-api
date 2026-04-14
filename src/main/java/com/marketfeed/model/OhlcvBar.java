package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class OhlcvBar {
    private LocalDate date;     // daily bars
    private Long timestampEpoch; // intraday bars (epoch seconds)
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
}
