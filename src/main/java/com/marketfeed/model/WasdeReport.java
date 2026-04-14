package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WasdeReport {
    private String commodity;
    private String commodityCode;
    private int marketYear;
    private Double production;      // 1000 metric tons
    private Double consumption;
    private Double exports;
    private Double endingStocks;
    private String unit;
}
