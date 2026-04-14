package com.marketfeed.model;

import lombok.Data;

import java.util.List;

@Data
public class AgentRequest {
    /** Natural language question, e.g. "What's driving corn futures up this week?" */
    private String question;

    /**
     * Optional: symbols to focus on. If omitted, the agent infers them from the question.
     * e.g. ["ZC=F", "ZW=F"] or ["AAPL", "MSFT"]
     */
    private List<String> symbols;
}
