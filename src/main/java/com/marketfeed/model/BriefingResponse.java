package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class BriefingResponse {
    private List<TickerBriefing> briefings;
    private Instant generatedAt;
    private String error;
}
