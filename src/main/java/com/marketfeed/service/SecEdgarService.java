package com.marketfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketfeed.model.FilingInfo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class SecEdgarService {

    private static final String SUBMISSIONS_BASE = "https://data.sec.gov/submissions";
    private static final String ARCHIVES_BASE    = "https://www.sec.gov/Archives/edgar/data";
    private static final String TICKERS_URL      = "https://www.sec.gov/files/company_tickers.json";
    private static final String SEC_VIEWER_BASE  = "https://www.sec.gov/Archives/edgar/data";
    private static final int    MAX_SECTION_CHARS = 12000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${market-feed.edgar.user-agent:AlphaFeed research@alphafeed.com}")
    private String userAgent;

    private Map<String, Integer> tickerToCik;
    private Instant tickerMapLoadedAt;

    public SecEdgarService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ─── CIK lookup ──────────────────────────────────────────────────────────────

    public Integer getCik(String ticker) {
        ensureTickerMap();
        return tickerToCik != null ? tickerToCik.get(ticker.toUpperCase()) : null;
    }

    @SuppressWarnings("unchecked")
    private void ensureTickerMap() {
        if (tickerToCik != null && tickerMapLoadedAt != null &&
                java.time.Duration.between(tickerMapLoadedAt, Instant.now()).toHours() < 24) {
            return;
        }
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    TICKERS_URL, HttpMethod.GET, new HttpEntity<>(edgarHeaders()), Map.class);
            if (resp.getBody() == null) return;

            Map<String, Map<String, Object>> data = resp.getBody();
            Map<String, Integer> map = new HashMap<>(data.size());
            for (Map<String, Object> entry : data.values()) {
                String t   = ((String) entry.get("ticker")).toUpperCase();
                Integer cik = ((Number) entry.get("cik_str")).intValue();
                map.put(t, cik);
            }
            tickerToCik = map;
            tickerMapLoadedAt = Instant.now();
            log.info("Loaded {} tickers from EDGAR", tickerToCik.size());
        } catch (Exception e) {
            log.warn("Failed to load EDGAR ticker map: {}", e.getMessage());
            tickerToCik = new HashMap<>();
        }
    }

    // ─── Filings list ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<FilingInfo> getFilings(String ticker, String formType, int limit) {
        Integer cik = getCik(ticker);
        if (cik == null) {
            log.warn("CIK not found for ticker: {}", ticker);
            return List.of();
        }
        String cikPadded = String.format("%010d", cik);
        String url = SUBMISSIONS_BASE + "/CIK" + cikPadded + ".json";

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(edgarHeaders()), Map.class);
            if (resp.getBody() == null) return List.of();

            Map<String, Object> filings = (Map<String, Object>) resp.getBody().get("filings");
            Map<String, Object> recent  = (Map<String, Object>) filings.get("recent");

            List<String> forms       = (List<String>) recent.get("form");
            List<String> dates       = (List<String>) recent.get("filingDate");
            List<String> accessions  = (List<String>) recent.get("accessionNumber");
            List<String> primaryDocs = (List<String>) recent.get("primaryDocument");

            List<FilingInfo> result = new ArrayList<>();
            for (int i = 0; i < forms.size() && result.size() < limit; i++) {
                if (!formType.equals(forms.get(i))) continue;
                String acc       = accessions.get(i);
                String accClean  = acc.replace("-", "");
                String docUrl    = ARCHIVES_BASE + "/" + cik + "/" + accClean + "/" + primaryDocs.get(i);
                String secUrl    = "https://www.sec.gov/Archives/edgar/data/" + cik + "/" + accClean + "/" + primaryDocs.get(i);

                result.add(FilingInfo.builder()
                        .accessionNumber(acc)
                        .filingDate(dates.get(i))
                        .form(forms.get(i))
                        .primaryDocument(primaryDocs.get(i))
                        .filingUrl(docUrl)
                        .secUrl(secUrl)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to fetch EDGAR filings for {}: {}", ticker, e.getMessage());
            return List.of();
        }
    }

    // ─── Document fetch + section extraction ─────────────────────────────────────

    public Map<String, String> fetchAndParseSections(FilingInfo filing) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    filing.getFilingUrl(), HttpMethod.GET,
                    new HttpEntity<>(edgarHeaders()), String.class);
            if (resp.getBody() == null) return Map.of();

            Document doc = Jsoup.parse(resp.getBody());
            // Remove boilerplate
            doc.select("script, style, table").remove();
            String text = doc.text();

            Map<String, String> sections = new LinkedHashMap<>();
            sections.put("Business Description", extractSection(text,
                    new String[]{"Item 1.", "ITEM 1.", "Item 1.", "ITEM 1."},
                    new String[]{"Item 1A", "ITEM 1A", "Item 1A"}));
            sections.put("Risk Factors", extractSection(text,
                    new String[]{"Item 1A", "ITEM 1A"},
                    new String[]{"Item 1B", "ITEM 1B", "Item 2.", "ITEM 2."}));
            sections.put("Management Discussion", extractSection(text,
                    new String[]{"Item 7.", "ITEM 7.", "Item 7.", "MANAGEMENT'S DISCUSSION", "Management's Discussion"},
                    new String[]{"Item 7A", "ITEM 7A", "Item 8.", "ITEM 8."}));

            return sections;
        } catch (Exception e) {
            log.warn("Failed to fetch/parse filing {}: {}", filing.getFilingUrl(), e.getMessage());
            return Map.of();
        }
    }

    private String extractSection(String text, String[] startMarkers, String[] endMarkers) {
        // Find the occurrence with the most content — skips TOC entries
        int bestStart   = -1;
        int bestLen     = 0;

        for (String marker : startMarkers) {
            int idx = 0;
            while (true) {
                idx = text.indexOf(marker, idx);
                if (idx == -1) break;
                int nextSection = text.length();
                for (String end : endMarkers) {
                    int ei = text.indexOf(end, idx + 200);
                    if (ei != -1 && ei < nextSection) nextSection = ei;
                }
                int len = nextSection - idx;
                if (len > bestLen) {
                    bestLen  = len;
                    bestStart = idx;
                }
                idx += marker.length();
            }
        }

        if (bestStart == -1 || bestLen < 500) return "";
        int end = bestStart + Math.min(bestLen, MAX_SECTION_CHARS);
        return text.substring(bestStart, end).trim();
    }

    // ─── 8-K text extraction (for earnings releases) ─────────────────────────────

    public String fetchEarningsText(FilingInfo filing) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    filing.getFilingUrl(), HttpMethod.GET,
                    new HttpEntity<>(edgarHeaders()), String.class);
            if (resp.getBody() == null) return "";

            Document doc = Jsoup.parse(resp.getBody());
            doc.select("script, style").remove();
            String text = doc.text();
            return text.length() > 5000 ? text.substring(0, 5000) : text;
        } catch (Exception e) {
            log.warn("Failed to fetch 8-K text {}: {}", filing.getFilingUrl(), e.getMessage());
            return "";
        }
    }

    // ─── Headers ──────────────────────────────────────────────────────────────────

    private HttpHeaders edgarHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent", userAgent);
        h.set("Accept", "application/json, text/html, */*");
        return h;
    }
}
