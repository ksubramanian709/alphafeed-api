package com.marketfeed.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class QuoteUpdatedEvent {
    private final String symbol;
    private final double price;
    private final double change;
    private final double changePercent;
    private final double high;
    private final double low;
    private final double volume;
    private final String source;
    private final Instant timestamp;
}
