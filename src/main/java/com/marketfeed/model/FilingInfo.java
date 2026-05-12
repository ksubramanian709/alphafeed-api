package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FilingInfo {
    private String accessionNumber;
    private String filingDate;
    private String form;
    private String primaryDocument;
    private String filingUrl;
    private String secUrl;
}
