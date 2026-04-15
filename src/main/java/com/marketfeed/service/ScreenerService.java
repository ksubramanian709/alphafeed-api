package com.marketfeed.service;

import com.marketfeed.model.Fundamentals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenerService {

    private final FundamentalsService fundamentalsService;

    // ── Universe of ~120 well-known stocks ────────────────────────────────────
    private static final List<String> UNIVERSE = List.of(
        // Technology
        "AAPL","MSFT","NVDA","GOOGL","META","AMZN","AMD","INTC","CRM","ORCL",
        "ADBE","QCOM","AVGO","TXN","ASML","TSM","SNOW","PLTR","UBER","NOW",
        "INTU","AMAT","MU","LRCX","KLAC","PANW","CRWD","ZS","NET","DDOG",
        // Financials
        "JPM","BAC","WFC","GS","MS","V","MA","PYPL","AXP","BLK","C","SCHW","COF",
        // Healthcare
        "UNH","JNJ","PFE","ABBV","MRK","LLY","AMGN","GILD","BMY","CVS","CI","ISRG","REGN",
        // Consumer Discretionary
        "TSLA","HD","LOW","NKE","MCD","SBUX","TGT","COST","TJX","CMG","ABNB","BKNG",
        // Consumer Staples
        "WMT","PG","KO","PEP","PM","MO","MDLZ","CL","GIS","KHC",
        // Energy
        "XOM","CVX","COP","SLB","EOG","PSX","VLO","MPC","OXY","HES",
        // Industrials
        "BA","CAT","GE","HON","UPS","FDX","RTX","LMT","NOC","DE","MMM","ETN","EMR",
        // Communication Services
        "NFLX","DIS","CMCSA","T","VZ","TMUS","CHTR","SNAP","PINS",
        // Utilities
        "NEE","DUK","SO","AEP","D","EXC","SRE",
        // Real Estate
        "AMT","PLD","CCI","EQIX","SPG","O","WELL",
        // Materials
        "LIN","APD","ECL","DOW","NEM","FCX","ALB"
    );

    // ── Fetch entire universe in parallel ─────────────────────────────────────
    @Cacheable(value = "screener-universe", key = "'all'", unless = "#result == null || #result.isEmpty()")
    public List<Fundamentals> getUniverse() {
        log.info("Loading screener universe ({} symbols)…", UNIVERSE.size());
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<CompletableFuture<Fundamentals>> futures = UNIVERSE.stream()
                .map(sym -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fundamentalsService.getFundamentals(sym);
                    } catch (Exception e) {
                        log.debug("Screener: skipping {} — {}", sym, e.getMessage());
                        return null;
                    }
                }, pool))
                .toList();

            return futures.stream()
                .map(f -> {
                    try { return f.get(10, TimeUnit.SECONDS); }
                    catch (Exception e) { return null; }
                })
                .filter(f -> f != null && f.getError() == null)
                .collect(Collectors.toList());
        } finally {
            pool.shutdown();
        }
    }

    // ── Screen with filters ───────────────────────────────────────────────────
    public List<Fundamentals> screen(ScreenerParams p) {
        List<Fundamentals> universe = getUniverse();
        return universe.stream()
            .filter(f -> p.sector()         == null || p.sector().isBlank()
                         || p.sector().equalsIgnoreCase(f.getSector()))
            .filter(f -> p.minPE()          == null || (f.getPeRatio()          != null && f.getPeRatio()          >= p.minPE()))
            .filter(f -> p.maxPE()          == null || (f.getPeRatio()          != null && f.getPeRatio()          <= p.maxPE()))
            .filter(f -> p.minMarketCapB()  == null || (f.getMarketCap()        != null && f.getMarketCap()        >= p.minMarketCapB() * 1_000_000_000L))
            .filter(f -> p.maxMarketCapB()  == null || (f.getMarketCap()        != null && f.getMarketCap()        <= p.maxMarketCapB() * 1_000_000_000L))
            .filter(f -> p.minProfitMargin()== null || (f.getProfitMargin()     != null && f.getProfitMargin()     >= p.minProfitMargin() / 100.0))
            .filter(f -> p.minROE()         == null || (f.getReturnOnEquity()   != null && f.getReturnOnEquity()   >= p.minROE() / 100.0))
            .filter(f -> p.minRevGrowth()   == null || (f.getRevenueGrowthYoy() != null && f.getRevenueGrowthYoy()>= p.minRevGrowth() / 100.0))
            .filter(f -> p.maxBeta()        == null || (f.getBeta()             != null && f.getBeta()             <= p.maxBeta()))
            .filter(f -> p.minDivYield()    == null || (f.getDividendYield()    != null && f.getDividendYield()    >= p.minDivYield() / 100.0))
            .sorted(comparator(p.sortBy(), p.sortDesc()))
            .collect(Collectors.toList());
    }

    // ── Available sectors ─────────────────────────────────────────────────────
    public List<String> getSectors() {
        return getUniverse().stream()
            .map(Fundamentals::getSector)
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Comparator<Fundamentals> comparator(String sortBy, boolean desc) {
        Comparator<Fundamentals> c = switch (sortBy == null ? "marketCap" : sortBy) {
            case "peRatio"          -> Comparator.comparingDouble(f -> nvl(f.getPeRatio()));
            case "forwardPE"        -> Comparator.comparingDouble(f -> nvl(f.getForwardPE()));
            case "profitMargin"     -> Comparator.comparingDouble(f -> nvl(f.getProfitMargin()));
            case "revenueGrowthYoy" -> Comparator.comparingDouble(f -> nvl(f.getRevenueGrowthYoy()));
            case "returnOnEquity"   -> Comparator.comparingDouble(f -> nvl(f.getReturnOnEquity()));
            case "beta"             -> Comparator.comparingDouble(f -> nvl(f.getBeta()));
            case "dividendYield"    -> Comparator.comparingDouble(f -> nvl(f.getDividendYield()));
            case "changePercent"    -> Comparator.comparingDouble(f -> nvl(f.getChangePercent()));
            case "eps"              -> Comparator.comparingDouble(f -> nvl(f.getEps()));
            default                 -> Comparator.comparingDouble(f -> nvl((double)(f.getMarketCap() != null ? f.getMarketCap() : 0L)));
        };
        return desc ? c.reversed() : c;
    }

    private double nvl(Double d) { return d != null ? d : 0.0; }

    // ── Filter params ─────────────────────────────────────────────────────────
    public record ScreenerParams(
        String  sector,
        Double  minPE,
        Double  maxPE,
        Double  minMarketCapB,
        Double  maxMarketCapB,
        Double  minProfitMargin,   // percent (0–100)
        Double  minROE,            // percent (0–100)
        Double  minRevGrowth,      // percent (can be negative)
        Double  maxBeta,
        Double  minDivYield,       // percent (0–100)
        String  sortBy,
        boolean sortDesc
    ) {}
}
