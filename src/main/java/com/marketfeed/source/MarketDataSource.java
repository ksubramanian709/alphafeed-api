package com.marketfeed.source;

import com.marketfeed.model.OhlcvBar;
import com.marketfeed.model.Quote;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketDataSource {
    String getName();
    Optional<Quote> getQuote(String symbol);
    List<OhlcvBar> getHistory(String symbol, LocalDate from, LocalDate to);
    boolean isAvailable();
}
