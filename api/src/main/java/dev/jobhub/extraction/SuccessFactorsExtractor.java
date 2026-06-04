package dev.jobhub.extraction;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SuccessFactorsExtractor implements JobExtractor {

    private static final int PAGE_SIZE = 25;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final long DETAIL_FETCH_DELAY_MS = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final Pattern TOTAL_COUNT_PATTERN = Pattern.compile("Results\\s+\\d+\\s*[–-]\\s*\\d+\\s+of\\s+(\\d+)");
    private static final Pattern ARIA_TOTAL_PATTERN = Pattern.compile("Results\\s+\\d+\\s+to\\s+\\d+\\s+of\\s+(\\d+)");

    private final WebClient webClient;

    public SuccessFactorsExtractor(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.SUCCESSFACTORS);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getUrl() != null && !endpoint.getUrl().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        String baseUrl = normalizeBaseUrl(endpoint.getUrl());
        Instant start = Instant.now();

        try {
            // Fetch first page to determine total count
            String firstPageHtml = fetchSearchPage(baseUrl, 0);
            if (firstPageHtml == null || firstPageHtml.isBlank()) {
                log.info("SuccessFactors [{}]: empty response", baseUrl);
                return ExtractionResult.empty(elapsed(start));
            }

            int totalCount = parseTotalCount(firstPageHtml);
            if (totalCount == 0) {
                log.info("SuccessFactors [{}]: no jobs found", baseUrl);
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("SuccessFactors [{}]: page 1, found {} total jobs", baseUrl, totalCount);

            // Parse first page
            List<JobListing> allListings = new ArrayList<>(parseListings(firstPageHtml, baseUrl));

            // Fetch remaining pages
            int totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);
            for (int page = 2; page <= totalPages; page++) {
                int offset = (page - 1) * PAGE_SIZE;
                try {
                    String pageHtml = fetchSearchPage(baseUrl, offset);
                    if (pageHtml != null && !pageHtml.isBlank()) {
                        List<JobListing> pageListings = parseListings(pageHtml, baseUrl);
                        allListings.addAll(pageListings);
                        log.info("SuccessFactors [{}]: page {}/{}, accumulated {} listings",
                                baseUrl, page, totalPages, allListings.size());
                    }
                } catch (Exception e) {
                    log.warn("SuccessFactors [{}]: failed to fetch page {} (offset {}): {}",
                            baseUrl, page, offset, e.getMessage());
                }
            }

            if (allListings.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            // Fetch detail pages for descriptions
            List<RawJobData> jobs = new ArrayList<>();
            for (int i = 0; i < allListings.size(); i++) {
                JobListing listing = allListings.get(i);
                String description = fetchDescription(listing.url());
                if (i > 0 && i % 50 == 0) {
                    log.info("SuccessFactors [{}]: fetched {}/{} detail pages",
                            baseUrl, i, allListings.size());
                }

                jobs.add(new RawJobData(
                        listing.externalId(),
                        listing.title(),
                        listing.location(),
                        description,
                        listing.url(),
                        null,
                        null,
                        null,
                        null,
                        null
                ));

                // Polite delay between detail fetches
                if (i < allListings.size() - 1) {
                    sleep(DETAIL_FETCH_DELAY_MS);
                }
            }

            log.info("SuccessFactors [{}]: extracted {} jobs", baseUrl, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("SuccessFactors [{}]: access forbidden (403)", baseUrl);
            return ExtractionResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("SuccessFactors [{}]: unauthorized (401)", baseUrl);
            return ExtractionResult.protectedEndpoint(elapsed(start));
        } catch (Exception e) {
            log.error("SuccessFactors [{}]: extraction failed: {}", baseUrl, e.getMessage());
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private String fetchSearchPage(String baseUrl, int startRow) {
        String url = baseUrl + "/search/?q=&locationsearch=Germany&locale=en_US&startrow=" + startRow;
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    private String fetchDetailPage(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    int parseTotalCount(String html) {
        // Try aria-label first: 'Results 1 to 25 of 303'
        Matcher ariaMatcher = ARIA_TOTAL_PATTERN.matcher(html);
        if (ariaMatcher.find()) {
            try {
                return Integer.parseInt(ariaMatcher.group(1));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        // Fallback: strip inline tags and try 'Results 1 – 25 of 303'
        String stripped = html.replaceAll("</?b>", "");
        Matcher matcher = TOTAL_COUNT_PATTERN.matcher(stripped);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    List<JobListing> parseListings(String html, String baseUrl) {
        Document doc = Jsoup.parse(html);
        List<JobListing> listings = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // Try table rows first (most SuccessFactors sites use a table layout)
        Elements tableRows = doc.select("tr");
        for (Element row : tableRows) {
            Element link = row.selectFirst("a[href~=/job/.+/\\d+/]");
            if (link == null) continue;

            try {
                String href = link.attr("href");
                String externalId = extractExternalId(href);
                String title = link.text().trim();

                if (externalId == null || externalId.isBlank() || title.isBlank()) {
                    continue;
                }
                if (!seenIds.add(externalId)) {
                    continue;
                }

                String fullUrl = toAbsoluteUrl(href, baseUrl);

                // Extract location from sibling table cell or text node
                String location = null;
                Elements cells = row.select("td");
                if (cells.size() >= 2) {
                    location = cells.get(1).text().trim();
                }
                if (location == null || location.isBlank()) {
                    location = extractLocationFromUrl(href);
                }

                listings.add(new JobListing(externalId, title, truncate(location, 500), fullUrl));
            } catch (Exception e) {
                log.debug("SuccessFactors: failed to parse listing row: {}", e.getMessage());
            }
        }

        // Fallback: if no table rows found, try bare links
        if (listings.isEmpty()) {
            Elements links = doc.select("a[href~=/job/.+/\\d+/]");
            for (Element link : links) {
                try {
                    String href = link.attr("href");
                    String externalId = extractExternalId(href);
                    String title = link.text().trim();

                    if (externalId == null || externalId.isBlank() || title.isBlank()) {
                        continue;
                    }
                    if (!seenIds.add(externalId)) {
                        continue;
                    }

                    String fullUrl = toAbsoluteUrl(href, baseUrl);
                    String location = extractLocationFromUrl(href);
                    listings.add(new JobListing(externalId, title, truncate(location, 500), fullUrl));
                } catch (Exception e) {
                    log.debug("SuccessFactors: failed to parse listing link: {}", e.getMessage());
                }
            }
        }

        return listings;
    }

    private String fetchDescription(String jobUrl) {
        try {
            String html = fetchDetailPage(jobUrl);
            if (html == null || html.isBlank()) {
                return null;
            }
            return parseDescription(html);
        } catch (Exception e) {
            log.debug("SuccessFactors: failed to fetch detail page {}: {}", jobUrl, e.getMessage());
            return null;
        }
    }

    String parseDescription(String html) {
        Document doc = Jsoup.parse(html);

        // Try common SuccessFactors description containers
        Element descriptionEl = doc.selectFirst(".jobdescription");
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst(".job-description");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst("[class*=jobDescription]");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst(".contentWithSidePanel__content");
        }
        if (descriptionEl == null) {
            // Fallback: main content area
            descriptionEl = doc.selectFirst("main");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst("#content");
        }

        if (descriptionEl == null) {
            return null;
        }

        String text = descriptionEl.text().trim();
        return truncate(text, MAX_DESCRIPTION_LENGTH);
    }

    String extractExternalId(String href) {
        // URL pattern: /job/{...}/{NumericId}/
        String cleaned = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String candidate = cleaned.substring(lastSlash + 1);
        // Verify it's numeric
        if (candidate.matches("\\d+")) {
            return candidate;
        }
        return null;
    }

    private String extractLocationFromUrl(String href) {
        // Pattern: /job/{City}-{Title}-{PostalCode}/{NumericId}/
        // Extract the city from the first segment after /job/
        String path = href.startsWith("http") ? href.replaceFirst("https?://[^/]+", "") : href;
        String[] segments = path.split("/");
        // segments: ["", "job", "{City}-{Title}-{PostalCode}", "{NumericId}"]
        if (segments.length >= 3) {
            String jobSegment = segments[2];
            // City is the first part before the first hyphen
            int firstHyphen = jobSegment.indexOf('-');
            if (firstHyphen > 0) {
                return jobSegment.substring(0, firstHyphen).replace("%20", " ");
            }
            return jobSegment;
        }
        return null;
    }

    private String toAbsoluteUrl(String href, String baseUrl) {
        if (href.startsWith("http")) {
            return href;
        }
        return baseUrl + (href.startsWith("/") ? href : "/" + href);
    }

    private String normalizeBaseUrl(String url) {
        // Remove trailing slash
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Internal record for intermediate listing data
    record JobListing(String externalId, String title, String location, String url) {}
}
