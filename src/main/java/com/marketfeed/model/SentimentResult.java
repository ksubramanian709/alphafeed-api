package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class SentimentResult {

    private String symbol;
    private TodaySentiment today;
    private List<PricePoint> priceHistory;
    private Instant fetchedAt;
    private String error;

    @Data
    @Builder
    public static class TodaySentiment {
        private LocalDate date;
        private double score;        // -1.0 to +1.0
        private int mentionCount;
        private String label;        // "bullish" | "neutral" | "bearish"
    }

    @Data
    @Builder
    public static class PricePoint {
        private LocalDate date;
        private double close;
        private double changePercent;
    }
}
