package com.marketfeed.model;

import lombok.Data;

import java.util.List;

@Data
public class BriefingRequest {
    private List<String> symbols;
}
