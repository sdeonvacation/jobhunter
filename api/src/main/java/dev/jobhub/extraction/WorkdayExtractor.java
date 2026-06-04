package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class WorkdayExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://%s.wd%s.myworkdayjobs.com";
    private static final String API_PATH = "/wday/cxs/%s/%s/jobs";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 100;
    private static final Pattern POSTED_DAYS_PATTERN = Pattern.compile("Posted (\\d+) Days? Ago");
    private static final Pattern SITE_PATH_PATTERN = Pattern.compile("/([^/]+)/?$");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkdayExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    WorkdayExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.WORKDAY);
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String tenant = endpoint.getAtsSlug();
        String shardId = endpoint.getAtsShardId();
        String site = extractSite(endpoint.getUrl());

        if (site == null) {
            log.warn("Workday [{}]: could not extract site from URL: {}", tenant, endpoint.getUrl());
            return ExtractionResult.error("Could not extract site from URL", elapsed(start));
        }

        String baseUrl = String.format(baseUrlTemplate, tenant, shardId);
        String apiPath = String.format(API_PATH, tenant, site);

        try {
            List<RawJobData> allJobs = new ArrayList<>();
            int offset = 0;
            int total = Integer.MAX_VALUE;

            while (offset < total && allJobs.size() < MAX_PAGES * PAGE_SIZE) {
                JsonNode page = fetchPage(baseUrl, apiPath, offset);

                if (page == null) {
                    break;
                }

                // Workday only returns total on first page; subsequent pages return 0
                int pageTotal = page.path("total").asInt(0);
                if (pageTotal > 0) {
                    total = pageTotal;
                }
                JsonNode postings = page.path("jobPostings");

                if (!postings.isArray() || postings.isEmpty()) {
                    break;
                }

                for (JsonNode posting : postings) {
                    RawJobData job = mapJob(posting, baseUrl);
                    if (job != null) {
                        allJobs.add(job);
                    }
                }

                offset += PAGE_SIZE;
            }

            if (allJobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Workday [{}]: extracted {} jobs (total: {})", tenant, allJobs.size(), total);
            return ExtractionResult.success(allJobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("Workday [{}]: protected instance (403)", tenant);
            return ExtractionResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Workday [{}]: HTTP {} - {}", tenant, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Workday [{}]: extraction failed", tenant, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.WORKDAY
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank()
                && endpoint.getAtsShardId() != null
                && !endpoint.getAtsShardId().isBlank();
    }

    private JsonNode fetchPage(String baseUrl, String apiPath, int offset) {
        String requestBody = buildRequestBody(offset, true);
        String url = baseUrl + apiPath;

        try {
            String response = postRequest(url, requestBody);
            return objectMapper.readTree(response);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 422) {
                // Some Workday instances reject sortBy - retry without it
                log.debug("Workday: 422 on request with sortBy, retrying without");
                String fallbackBody = buildRequestBody(offset, false);
                try {
                    String response = postRequest(url, fallbackBody);
                    return objectMapper.readTree(response);
                } catch (WebClientResponseException retryEx) {
                    throw retryEx;
                } catch (Exception retryEx) {
                    throw new RuntimeException("Workday extraction failed after sortBy retry", retryEx);
                }
            }
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Workday page at offset " + offset, e);
        }
    }

    private String postRequest(String url, String body) {
        return webClient.post()
                .uri(URI.create(url))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(45));
    }

    private String buildRequestBody(int offset, boolean includeSortBy) {
        if (includeSortBy) {
            return String.format(
                    "{\"appliedFacets\":{},\"limit\":%d,\"offset\":%d,\"searchText\":\"\",\"sortBy\":\"mostRelevant\"}",
                    PAGE_SIZE, offset
            );
        }
        return String.format(
                "{\"appliedFacets\":{},\"limit\":%d,\"offset\":%d,\"searchText\":\"\"}",
                PAGE_SIZE, offset
        );
    }

    private RawJobData mapJob(JsonNode node, String baseUrl) {
        try {
            String externalPath = node.path("externalPath").asText(null);
            String title = truncate(node.path("title").asText(null), 500);
            String location = truncate(node.path("locationsText").asText(null), 500);
            String postedOnRaw = node.path("postedOn").asText(null);
            String rawJson = node.toString();

            // Build description from bulletFields
            StringBuilder descBuilder = new StringBuilder();
            JsonNode bulletFields = node.path("bulletFields");
            if (bulletFields.isArray()) {
                for (JsonNode field : bulletFields) {
                    if (descBuilder.length() > 0) {
                        descBuilder.append(" | ");
                    }
                    descBuilder.append(field.asText());
                }
            }

            String applyUrl = externalPath != null ? baseUrl + externalPath : null;
            LocalDate postedDate = parsePostedOn(postedOnRaw);

            return new RawJobData(
                    externalPath, title, location, descBuilder.toString(), applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Workday: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate parsePostedOn(String postedOn) {
        if (postedOn == null || postedOn.isBlank()) {
            return null;
        }
        if (postedOn.contains("Today") || postedOn.contains("today")) {
            return LocalDate.now();
        }
        if (postedOn.contains("Yesterday") || postedOn.contains("yesterday")) {
            return LocalDate.now().minusDays(1);
        }
        Matcher matcher = POSTED_DAYS_PATTERN.matcher(postedOn);
        if (matcher.find()) {
            int days = Integer.parseInt(matcher.group(1));
            return LocalDate.now().minusDays(days);
        }
        // "Posted 30+ Days Ago" pattern
        if (postedOn.contains("30+")) {
            return LocalDate.now().minusDays(30);
        }
        return null;
    }

    String extractSite(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        // Remove trailing slash and query params
        String path = url.split("\\?")[0].replaceAll("/+$", "");
        Matcher matcher = SITE_PATH_PATTERN.matcher(path);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
