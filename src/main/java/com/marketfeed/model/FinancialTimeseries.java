package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class FinancialTimeseries {
    private String symbol;
    private String companyName;

    // Annual snapshots keyed by fiscal year e.g. "2024"
    private List<AnnualSnapshot> annual;

    // Most recent calculated metrics
    private Double grossMarginPct;
    private Double operatingMarginPct;
    private Double netMarginPct;
    private Double revenueGrowthYoy;
    private Double epsGrowthYoy;

    private String error;

    @Data
    @Builder
    public static class AnnualSnapshot {
        private String fiscalYear;
        private String filingDate;
        private Long   revenue;
        private Long   grossProfit;
        private Long   operatingIncome;
        private Long   netIncome;
        private Long   researchAndDevelopment;
        private Long   capex;
        private Long   cashAndEquivalents;
        private Long   longTermDebt;
        private Long   totalAssets;
        private Double epsBasic;
        private Double grossMarginPct;
        private Double operatingMarginPct;
        private Double netMarginPct;
    }
}
