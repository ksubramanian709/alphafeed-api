package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TickerBriefing {
    private String symbol;
    private String name;
    private double price;
    private double changePercent;
    private String sentiment;    // bullish | bearish | neutral
    private String summary;
    private List<String> keyPoints;
}
