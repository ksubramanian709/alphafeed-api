package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Fundamentals {
    private String symbol;
    private String name;
    private String description;
    private String sector;
    private String industry;
    private String exchange;

    // Valuation
    private Double peRatio;
    private Double forwardPE;
    private Double pegRatio;
    private Double priceToBook;
    private Double priceToSales;
    private Double evToEbitda;
    private Double evToRevenue;

    // Size & profitability
    private Long   marketCap;
    private Long   revenueTtm;
    private Long   grossProfitTtm;
    private Long   ebitda;
    private Double eps;
    private Double dilutedEpsTtm;
    private Double profitMargin;
    private Double operatingMargin;
    private Double returnOnEquity;
    private Double returnOnAssets;

    // Growth
    private Double revenueGrowthYoy;
    private Double earningsGrowthYoy;

    // Dividends
    private Double dividendPerShare;
    private Double dividendYield;

    // Analyst
    private Double analystTargetPrice;
    private Double beta;

    // Moving averages
    private Double ma50;
    private Double ma200;

    // Live price (from YF price module — populated in screener)
    private Double currentPrice;
    private Double changePercent;

    private String error;
}
