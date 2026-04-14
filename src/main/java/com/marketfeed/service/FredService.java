package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.EconomicIndicator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FredService {

    private final RestTemplate restTemplate;

    @Value("${market-feed.fred.api-key}")
    private String apiKey;

    @Value("${market-feed.fred.base-url}")
    private String baseUrl;

    // The indicators Barchart's users care about most
    public static final Map<String, SeriesMeta> SERIES = new LinkedHashMap<>();
    static {
        SERIES.put("FEDFUNDS", new SeriesMeta("Federal Funds Rate",        "Percent"));
        SERIES.put("DGS10",    new SeriesMeta("10-Year Treasury Yield",    "Percent"));
        SERIES.put("DGS2",     new SeriesMeta("2-Year Treasury Yield",     "Percent"));
        SERIES.put("CPIAUCSL", new SeriesMeta("CPI (All Urban Consumers)", "Index 1982-84=100"));
        SERIES.put("GDP",      new SeriesMeta("US GDP",                    "Billions USD"));
        SERIES.put("UNRATE",   new SeriesMeta("Unemployment Rate",         "Percent"));
        SERIES.put("DTWEXBGS", new SeriesMeta("US Dollar Index (Broad)",   "Index Mar 1973=100"));
    }

    /**
     * Fetch the latest value for a single FRED series. Cached 1 hour.
     */
    @Cacheable(value = "economic", key = "#seriesId.toUpperCase()")
    public ApiResponse<EconomicIndicator> getLatest(String seriesId) {
        String id = seriesId.toUpperCase();
        if (apiKey == null || apiKey.isBlank()) {
            return ApiResponse.error("FRED_API_KEY not configured — get a free key at fred.stlouisfed.org");
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/series/observations")
                    .queryParam("series_id", id)
                    .queryParam("api_key", apiKey)
                    .queryParam("file_type", "json")
                    .queryParam("limit", "1")
                    .queryParam("sort_order", "desc")
                    .toUriString();

            ObservationsResponse response = restTemplate.getForObject(url, ObservationsResponse.class);
            if (response == null || response.getObservations() == null || response.getObservations().isEmpty()) {
                return ApiResponse.error("No data for series: " + id);
            }

            Observation obs = response.getObservations().get(0);
            if (".".equals(obs.getValue())) {
                return ApiResponse.error("No current value available for: " + id);
            }

            SeriesMeta meta = SERIES.getOrDefault(id, new SeriesMeta(id, ""));
            EconomicIndicator indicator = EconomicIndicator.builder()
                    .seriesId(id)
                    .name(meta.name())
                    .description(meta.name())
                    .value(Double.parseDouble(obs.getValue()))
                    .unit(meta.unit())
                    .date(LocalDate.parse(obs.getDate()))
                    .build();

            return ApiResponse.success(indicator, "fred");

        } catch (Exception e) {
            log.warn("FRED fetch failed for {}: {}", id, e.getMessage());
            return ApiResponse.error("Failed to fetch " + id + ": " + e.getMessage());
        }
    }

    /**
     * Fetch all key macro indicators in one call. Cached 1 hour.
     */
    @Cacheable(value = "economic", key = "'macro_summary'")
    public ApiResponse<Map<String, EconomicIndicator>> getMacroSummary() {
        Map<String, EconomicIndicator> result = new LinkedHashMap<>();
        for (String seriesId : SERIES.keySet()) {
            ApiResponse<EconomicIndicator> r = getLatest(seriesId);
            if (r.getData() != null) {
                result.put(seriesId, r.getData());
            }
        }
        return ApiResponse.success(result, "fred");
    }

    public record SeriesMeta(String name, String unit) {}

    // ---------- response POJOs ----------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ObservationsResponse {
        private List<Observation> observations;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Observation {
        private String date;
        private String value;
    }
}
