package com.marketfeed.controller;

import com.marketfeed.model.SentimentResult;
import com.marketfeed.service.SentimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/sentiment")
@RequiredArgsConstructor
public class SentimentController {

    private final SentimentService sentimentService;

    @GetMapping("/{symbol}")
    public SentimentResult getSentiment(@PathVariable String symbol) {
        return sentimentService.getSentiment(symbol.toUpperCase());
    }
}
