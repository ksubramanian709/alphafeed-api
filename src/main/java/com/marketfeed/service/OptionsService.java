package com.marketfeed.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketfeed.model.ApiResponse;
import com.marketfeed.model.OptionsChain;
import com.marketfeed.model.OptionsContract;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsService {

    private final RestTemplate restTemplate;

    private static final String OPTIONS_URL =
            "https://query2.finance.yahoo.com/v7/finance/options/%s";
    private static final String OPTIONS_URL_WITH_DATE =
            "https://query2.finance.yahoo.com/v7/finance/options/%s?date=%d";

    /**
     * Fetch options chain for a symbol. If expirationEpoch is null, returns the nearest expiration.
     * Cached for 5 minutes.
     */
    @Cacheable(value = "options", key = "#symbol.toUpperCase() + '_' + #expirationEpoch")
    public ApiResponse<OptionsChain> getOptionsChain(String symbol, Long expirationEpoch) {
        String sym = symbol.toUpperCase();
        String url = expirationEpoch != null
                ? String.format(OPTIONS_URL_WITH_DATE, sym, expirationEpoch)
                : String.format(OPTIONS_URL, sym);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json");

            ResponseEntity<YahooOptionsResponse> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), YahooOptionsResponse.class);

            if (resp.getBody() == null) return ApiResponse.error("No response from Yahoo Finance");

            YahooOptionsResponse.OptionChain chain = resp.getBody().getOptionChain();
            if (chain == null || chain.getResult() == null || chain.getResult().isEmpty()) {
                return ApiResponse.error("No options data available for " + sym
                        + ". Options may not trade on this symbol.");
            }

            YahooOptionsResponse.Result result = chain.getResult().get(0);
            if (result.getOptions() == null || result.getOptions().isEmpty()) {
                return ApiResponse.error("No options contracts found for " + sym);
            }

            YahooOptionsResponse.OptionsSlice slice = result.getOptions().get(0);

            double underlyingPrice = result.getQuote() != null
                    ? result.getQuote().getRegularMarketPrice() : 0;

            List<OptionsContract> calls = mapContracts(slice.getCalls());
            List<OptionsContract> puts  = mapContracts(slice.getPuts());

            OptionsChain optionsChain = OptionsChain.builder()
                    .underlyingSymbol(sym)
                    .underlyingPrice(underlyingPrice)
                    .expirationDate(slice.getExpirationDate())
                    .allExpirationDates(result.getExpirationDates() != null
                            ? result.getExpirationDates() : Collections.emptyList())
                    .strikes(result.getStrikes() != null
                            ? result.getStrikes() : Collections.emptyList())
                    .calls(calls)
                    .puts(puts)
                    .build();

            return ApiResponse.success(optionsChain, "yahoo_finance");

        } catch (Exception e) {
            log.warn("Options fetch failed for {}: {}", sym, e.getMessage());
            return ApiResponse.error("Failed to fetch options for " + sym + ": " + e.getMessage());
        }
    }

    private List<OptionsContract> mapContracts(List<YahooOptionsResponse.Contract> raw) {
        if (raw == null) return Collections.emptyList();
        return raw.stream().map(c -> OptionsContract.builder()
                .contractSymbol(c.getContractSymbol())
                .strike(c.getStrike())
                .lastPrice(c.getLastPrice())
                .bid(c.getBid())
                .ask(c.getAsk())
                .change(c.getChange())
                .changePercent(c.getPercentChange())
                .volume(c.getVolume())
                .openInterest(c.getOpenInterest())
                .impliedVolatility(c.getImpliedVolatility())
                .inTheMoney(c.isInTheMoney())
                .expiration(c.getExpiration())
                .lastTradeDate(c.getLastTradeDate())
                .contractSize(c.getContractSize())
                .build()
        ).collect(Collectors.toList());
    }

    // ─── Yahoo Finance response POJOs ────────────────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class YahooOptionsResponse {
        private OptionChain optionChain;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class OptionChain {
            private List<Result> result;
            private Object error;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Result {
            private String       underlyingSymbol;
            private List<Long>   expirationDates;
            private List<Double> strikes;
            private Quote        quote;
            private List<OptionsSlice> options;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Quote {
            private double regularMarketPrice;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class OptionsSlice {
            private long           expirationDate;
            private List<Contract> calls;
            private List<Contract> puts;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Contract {
            private String  contractSymbol;
            private double  strike;
            private String  currency;
            private double  lastPrice;
            private double  change;
            @JsonProperty("percentChange")  private double  percentChange;
            private long    volume;
            private long    openInterest;
            private double  bid;
            private double  ask;
            private String  contractSize;
            private long    expiration;
            private long    lastTradeDate;
            @JsonProperty("impliedVolatility") private double impliedVolatility;
            @JsonProperty("inTheMoney")        private boolean inTheMoney;
        }
    }
}
