package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.WasdeReport;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.*;

/**
 * Pulls grain price data from Alpha Vantage's commodity endpoints.
 *
 * Alpha Vantage provides monthly price series for agricultural commodities
 * (corn, wheat, soybeans) using the same API key as quotes.
 *
 * Note: This delivers price series data. For full USDA WASDE S&D fundamentals
 * (production, consumption, exports, ending stocks), the USDA FAS PSD Online API
 * is the correct upstream source. The client code is structured to swap in that
 * source when a reliable endpoint is confirmed — see fetchFromUsdaPsd().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsdaService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.alpha-vantage.api-key}")
    private String alphaVantageKey;

    @Value("${market-feed.alpha-vantage.base-url}")
    private String avBaseUrl;

    // Alpha Vantage commodity function names
    private static final Map<String, String> COMMODITIES = new LinkedHashMap<>();
    static {
        COMMODITIES.put("CORN",     "Corn");
        COMMODITIES.put("WHEAT",    "Wheat");
        COMMODITIES.put("SOYBEANS", "Soybeans");
    }

    /**
     * Returns monthly price series for key grain commodities.
     * Cached 12 hours — aligns with WASDE monthly release cadence.
     */
    @Cacheable(value = "wasde")
    public ApiResponse<List<WasdeReport>> getGrainPrices() {
        if (alphaVantageKey == null || alphaVantageKey.equals("demo")) {
            return ApiResponse.error("ALPHA_VANTAGE_KEY not configured");
        }

        List<WasdeReport> reports = new ArrayList<>();

        for (Map.Entry<String, String> entry : COMMODITIES.entrySet()) {
            String function = entry.getKey();
            String name = entry.getValue();
            try {
                WasdeReport report = fetchCommodityPrice(function, name);
                if (report != null) reports.add(report);
            } catch (Exception e) {
                log.warn("Alpha Vantage commodity fetch failed for {}: {}", name, e.getMessage());
            }
        }

        if (reports.isEmpty()) {
            return ApiResponse.error("Failed to fetch grain price data");
        }

        return ApiResponse.<List<WasdeReport>>builder()
                .data(reports)
                .source("alpha_vantage_commodities")
                .cached(false)
                .fetchedAt(Instant.now())
                .build();
    }

    private WasdeReport fetchCommodityPrice(String function, String name) {
        String url = UriComponentsBuilder.fromHttpUrl(avBaseUrl)
                .queryParam("function", function)
                .queryParam("interval", "monthly")
                .queryParam("apikey", alphaVantageKey)
                .toUriString();

        CommodityResponse response = restTemplate.getForObject(url, CommodityResponse.class);
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return null;
        }

        // Most recent data point
        CommodityResponse.DataPoint latest = response.getData().get(0);
        double price = 0;
        try {
            price = Double.parseDouble(latest.getValue());
        } catch (NumberFormatException e) {
            return null;
        }

        return WasdeReport.builder()
                .commodity(name)
                .commodityCode(function)
                .marketYear(Integer.parseInt(latest.getDate().substring(0, 4)))
                // For price-based reports, use production field to carry the price value
                .production(price)
                .unit(response.getUnit() != null ? response.getUnit() : "USD/bushel")
                .build();
    }

    // ---------- response POJO ----------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommodityResponse {
        private String name;
        private String interval;
        private String unit;
        private List<DataPoint> data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DataPoint {
            private String date;
            private String value;
        }
    }
}
