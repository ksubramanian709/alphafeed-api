package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class EconomicIndicator {
    private String seriesId;
    private String name;
    private String description;
    private double value;
    private String unit;
    private LocalDate date;
}
