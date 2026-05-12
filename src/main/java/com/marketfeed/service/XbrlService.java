package com.marketfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.FinancialTimeseries;
import com.marketfeed.model.FinancialTimeseries.AnnualSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class XbrlService {

    private final SecEdgarService edgarService;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    objectMapper;

    @Value("${market-feed.edgar.user-agent:AlphaFeed research@alphafeed.com}")
    private String userAgent;

    private static final String COMPANY_FACTS_URL =
            "https://data.sec.gov/api/xbrl/companyfacts/CIK%s.json";

    // Ordered fallbacks — different companies use different GAAP concepts
    private static final String[] REVENUE_CONCEPTS = {
        "RevenueFromContractWithCustomerExcludingAssessedTax",
        "Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerIncludingAssessedTax",
        "SalesRevenueGoodsNet", "RevenueFromContractWithCustomerExcludingAssessedTaxAndInterestIncome"
    };
    private static final String[] NET_INCOME_CONCEPTS = {
        "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic"
    };
    private static final String[] GROSS_PROFIT_CONCEPTS = {
        "GrossProfit", "GrossProfitLoss"
    };
    private static final String[] OP_INCOME_CONCEPTS = {
        "OperatingIncomeLoss", "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"
    };
    private static final String[] EPS_CONCEPTS = {
        "EarningsPerShareBasic", "EarningsPerShareDiluted"
    };
    private static final String[] CASH_CONCEPTS = {
        "CashAndCashEquivalentsAtCarryingValue",
        "CashCashEquivalentsAndShortTermInvestments",
        "CashAndCashEquivalentsAndShortTermInvestments"
    };
    private static final String[] DEBT_CONCEPTS = {
        "LongTermDebt", "LongTermDebtNoncurrent", "LongTermDebtAndCapitalLeaseObligations"
    };
    private static final String[] ASSETS_CONCEPTS = {
        "Assets"
    };
    private static final String[] RD_CONCEPTS = {
        "ResearchAndDevelopmentExpense", "ResearchAndDevelopmentExpenseExcludingAcquiredInProcessCost"
    };
    private static final String[] CAPEX_CONCEPTS = {
        "PaymentsToAcquirePropertyPlantAndEquipment",
        "PaymentsForCapitalImprovements"
    };

    @Cacheable(value = "research-financials", key = "#symbol")
    public FinancialTimeseries getFinancials(String symbol) {
        Integer cik = edgarService.getCik(symbol);
        if (cik == null) {
            return FinancialTimeseries.builder().symbol(symbol)
                    .error("Company not found in SEC EDGAR").build();
        }

        String cikPadded = String.format("%010d", cik);
        String url       = String.format(COMPANY_FACTS_URL, cikPadded);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", userAgent);
            headers.set("Accept", "application/json");

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (resp.getBody() == null) throw new RuntimeException("Empty response");

            JsonNode root        = objectMapper.readTree(resp.getBody());
            String   companyName = root.path("entityName").asText(symbol);
            JsonNode usGaap      = root.path("facts").path("us-gaap");

            // Extract annual series for each concept
            Map<String, Map<Integer, Long>>   longSeries   = new LinkedHashMap<>();
            Map<String, Map<Integer, Double>> doubleSeries = new LinkedHashMap<>();

            extractLong(usGaap, "revenue",         REVENUE_CONCEPTS,   longSeries);
            extractLong(usGaap, "grossProfit",      GROSS_PROFIT_CONCEPTS, longSeries);
            extractLong(usGaap, "operatingIncome",  OP_INCOME_CONCEPTS, longSeries);
            extractLong(usGaap, "netIncome",        NET_INCOME_CONCEPTS, longSeries);
            extractLong(usGaap, "cash",             CASH_CONCEPTS,      longSeries);
            extractLong(usGaap, "debt",             DEBT_CONCEPTS,      longSeries);
            extractLong(usGaap, "assets",           ASSETS_CONCEPTS,    longSeries);
            extractLong(usGaap, "rd",               RD_CONCEPTS,        longSeries);
            extractLong(usGaap, "capex",            CAPEX_CONCEPTS,     longSeries);
            extractDouble(usGaap, "eps",            EPS_CONCEPTS,       doubleSeries);

            // Filing dates per fiscal year
            Map<Integer, String> filingDates = extractFilingDates(usGaap, REVENUE_CONCEPTS);

            // Union of fiscal years with revenue, last 8
            Set<Integer> years = new TreeSet<>(Collections.reverseOrder());
            if (longSeries.containsKey("revenue")) years.addAll(longSeries.get("revenue").keySet());
            List<Integer> topYears = years.stream().limit(8).collect(Collectors.toList());

            List<AnnualSnapshot> snapshots = new ArrayList<>();
            for (int fy : topYears) {
                Long rev  = longSeries.getOrDefault("revenue", Map.of()).get(fy);
                Long gp   = longSeries.getOrDefault("grossProfit", Map.of()).get(fy);
                Long op   = longSeries.getOrDefault("operatingIncome", Map.of()).get(fy);
                Long ni   = longSeries.getOrDefault("netIncome", Map.of()).get(fy);
                Long cash = longSeries.getOrDefault("cash", Map.of()).get(fy);
                Long debt = longSeries.getOrDefault("debt", Map.of()).get(fy);
                Long assets = longSeries.getOrDefault("assets", Map.of()).get(fy);
                Long rd   = longSeries.getOrDefault("rd", Map.of()).get(fy);
                Long capex = longSeries.getOrDefault("capex", Map.of()).get(fy);
                Double eps = doubleSeries.getOrDefault("eps", Map.of()).get(fy);

                Double gmPct = pct(gp, rev);
                Double omPct = pct(op, rev);
                Double nmPct = pct(ni, rev);

                snapshots.add(AnnualSnapshot.builder()
                        .fiscalYear(String.valueOf(fy))
                        .filingDate(filingDates.getOrDefault(fy, ""))
                        .revenue(rev)
                        .grossProfit(gp)
                        .operatingIncome(op)
                        .netIncome(ni)
                        .cashAndEquivalents(cash)
                        .longTermDebt(debt)
                        .totalAssets(assets)
                        .researchAndDevelopment(rd)
                        .capex(capex)
                        .epsBasic(eps)
                        .grossMarginPct(gmPct)
                        .operatingMarginPct(omPct)
                        .netMarginPct(nmPct)
                        .build());
            }

            // Current + prior year for growth rates
            Double revGrowth = null, epsGrowth = null;
            if (snapshots.size() >= 2) {
                Long r0 = snapshots.get(0).getRevenue();
                Long r1 = snapshots.get(1).getRevenue();
                if (r0 != null && r1 != null && r1 != 0)
                    revGrowth = 100.0 * (r0 - r1) / Math.abs(r1);

                Double e0 = snapshots.get(0).getEpsBasic();
                Double e1 = snapshots.get(1).getEpsBasic();
                if (e0 != null && e1 != null && e1 != 0)
                    epsGrowth = 100.0 * (e0 - e1) / Math.abs(e1);
            }

            AnnualSnapshot latest = snapshots.isEmpty() ? null : snapshots.get(0);

            return FinancialTimeseries.builder()
                    .symbol(symbol)
                    .companyName(companyName)
                    .annual(snapshots)
                    .grossMarginPct(latest != null ? latest.getGrossMarginPct() : null)
                    .operatingMarginPct(latest != null ? latest.getOperatingMarginPct() : null)
                    .netMarginPct(latest != null ? latest.getNetMarginPct() : null)
                    .revenueGrowthYoy(revGrowth)
                    .epsGrowthYoy(epsGrowth)
                    .build();

        } catch (Exception e) {
            log.warn("XBRL fetch failed for {}: {}", symbol, e.getMessage());
            return FinancialTimeseries.builder().symbol(symbol)
                    .error("Failed to fetch financial data: " + e.getMessage()).build();
        }
    }

    // ─── Extraction helpers ───────────────────────────────────────────────────────

    private void extractLong(JsonNode usGaap, String key, String[] concepts,
                              Map<String, Map<Integer, Long>> out) {
        for (String concept : concepts) {
            JsonNode node = usGaap.path(concept);
            if (node.isMissingNode()) continue;
            Map<Integer, Long> series = annualLongSeries(node);
            if (!series.isEmpty()) { out.put(key, series); return; }
        }
    }

    private void extractDouble(JsonNode usGaap, String key, String[] concepts,
                                Map<String, Map<Integer, Double>> out) {
        for (String concept : concepts) {
            JsonNode node = usGaap.path(concept);
            if (node.isMissingNode()) continue;
            Map<Integer, Double> series = annualDoubleSeries(node);
            if (!series.isEmpty()) { out.put(key, series); return; }
        }
    }

    private Map<Integer, Long> annualLongSeries(JsonNode concept) {
        Map<Integer, Long> result = new TreeMap<>();
        for (JsonNode unitGroup : concept.path("units")) {
            for (JsonNode fact : unitGroup) {
                String form = fact.path("form").asText("");
                String fp   = fact.path("fp").asText("");
                if (!"10-K".equals(form) || !"FY".equals(fp)) continue;
                int fy = fact.path("fy").asInt(0);
                if (fy < 2010) continue;
                long val = fact.path("val").asLong(0);
                result.merge(fy, val, (a, b) -> b); // keep latest filing for that FY
            }
        }
        return result;
    }

    private Map<Integer, Double> annualDoubleSeries(JsonNode concept) {
        Map<Integer, Double> result = new TreeMap<>();
        for (JsonNode unitGroup : concept.path("units")) {
            for (JsonNode fact : unitGroup) {
                String form = fact.path("form").asText("");
                String fp   = fact.path("fp").asText("");
                if (!"10-K".equals(form) || !"FY".equals(fp)) continue;
                int fy = fact.path("fy").asInt(0);
                if (fy < 2010) continue;
                double val = fact.path("val").asDouble(0);
                result.merge(fy, val, (a, b) -> b);
            }
        }
        return result;
    }

    private Map<Integer, String> extractFilingDates(JsonNode usGaap, String[] concepts) {
        for (String concept : concepts) {
            JsonNode node = usGaap.path(concept);
            if (node.isMissingNode()) continue;
            Map<Integer, String> dates = new TreeMap<>();
            for (JsonNode unitGroup : node.path("units")) {
                for (JsonNode fact : unitGroup) {
                    String form = fact.path("form").asText("");
                    String fp   = fact.path("fp").asText("");
                    if (!"10-K".equals(form) || !"FY".equals(fp)) continue;
                    int    fy   = fact.path("fy").asInt(0);
                    String filed = fact.path("filed").asText("");
                    if (fy > 0 && !filed.isEmpty()) dates.put(fy, filed);
                }
            }
            if (!dates.isEmpty()) return dates;
        }
        return Map.of();
    }

    private Double pct(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator == 0) return null;
        return 100.0 * numerator / denominator;
    }
}
