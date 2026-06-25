package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
public class WorkdayStrategy extends AbstractAtsStrategy {

    private static final String DEFAULT_BASE_URL = "https://%s.wd%s.myworkdayjobs.com";
    private static final String API_PATH = "/wday/cxs/%s/%s/jobs";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 100;
    private static final Duration PAGE_DELAY = Duration.ofMillis(300);
    private static final Pattern POSTED_DAYS_PATTERN = Pattern.compile("Posted (\\d+) Days? Ago");
    private static final Pattern SITE_PATH_PATTERN = Pattern.compile("/([^/]+)/?$");
    // Fallback: extract tenant + shard from URL when atsSlug/atsShardId not stored in DB
    private static final Pattern WORKDAY_URL_PATTERN = Pattern.compile(
            "https?://([\\w-]+)\\.wd(\\d+)\\.myworkdayjobs\\.com.*");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkdayStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    WorkdayStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.WORKDAY);
    }

    @Override
    public String name() {
        return "workday";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();
        String tenant = endpoint.getAtsSlug();
        String shardId = endpoint.getAtsShardId();
        String site = extractSite(endpoint.getUrl());

        // Fallback: parse tenant and shardId from the URL when not stored in DB.
        // URL format: https://{tenant}.wd{shard}.myworkdayjobs.com/{site}
        if ((tenant == null || shardId == null) && endpoint.getUrl() != null) {
            Matcher m = WORKDAY_URL_PATTERN.matcher(endpoint.getUrl());
            if (m.matches()) {
                if (tenant == null) tenant = m.group(1);
                if (shardId == null) shardId = m.group(2);
                log.debug("Workday [{}]: resolved tenant='{}' shardId='{}' from URL", endpoint.getUrl(), tenant, shardId);
            }
        }

        if (tenant == null || shardId == null) {
            log.warn("Workday [{}]: cannot determine tenant/shardId — atsSlug/atsShardId not set and URL does not match Workday pattern", endpoint.getUrl());
            return FetchResult.error("Cannot determine tenant or shardId", elapsed(start));
        }

        if (site == null) {
            log.warn("Workday [{}]: could not extract site from URL: {}", tenant, endpoint.getUrl());
            return FetchResult.error("Could not extract site from URL", elapsed(start));
        }

        String baseUrl = String.format(baseUrlTemplate, tenant, shardId);
        String apiPath = String.format(API_PATH, tenant, site);

        try {
            List<RawAggregatorJob> allJobs = new ArrayList<>();
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
                    RawAggregatorJob job = mapJob(posting, baseUrl);
                    if (job != null) {
                        allJobs.add(job);
                    }
                }

                offset += PAGE_SIZE;
                if (offset < total) {
                    try {
                        Thread.sleep(PAGE_DELAY.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (allJobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Workday [{}]: extracted {} jobs (total: {})", tenant, allJobs.size(), total);
            return FetchResult.success(allJobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("Workday [{}]: protected instance (403)", tenant);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Workday [{}]: HTTP {} - {}", tenant, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Workday [{}]: extraction failed", tenant, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
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

    private RawAggregatorJob mapJob(JsonNode node, String baseUrl) {
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

            return new RawAggregatorJob(
                    externalPath, title, null, location, descBuilder.toString(), applyUrl,
                    postedDate, null, null, null, rawJson
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

    /**
     * Fetches the full job description from the Workday job page (JSON-LD schema.org data).
     * The list API only returns bulletFields (short metadata), not the actual description.
     *
     * <p>URL format: {baseUrl}/{site}{externalPath} — e.g.
     * https://ag.wd3.myworkdayjobs.com/Airbus/job/Manching/Software-Developer_JR10422671
     *
     * @param endpoint   the career endpoint (provides tenant, shardId, site)
     * @param externalId the job's external_id, e.g. "/job/Munich/Title_JR123"
     * @return plain-text description extracted from JSON-LD, or null on failure
     */
    public String fetchDescription(CareerEndpoint endpoint, String externalId) {
        String tenant = endpoint.getAtsSlug();
        String shardId = endpoint.getAtsShardId();
        String site = extractSite(endpoint.getUrl());

        if (tenant == null || shardId == null) {
            Matcher m = WORKDAY_URL_PATTERN.matcher(endpoint.getUrl() != null ? endpoint.getUrl() : "");
            if (m.matches()) {
                if (tenant == null) tenant = m.group(1);
                if (shardId == null) shardId = m.group(2);
            }
        }

        if (tenant == null || shardId == null || site == null || externalId == null) {
            log.warn("Workday fetchDescription: missing tenant/shardId/site/externalId for endpoint {}", endpoint.getId());
            return null;
        }

        // Public job page: {baseUrl}/{site}{externalPath}
        // e.g. https://ag.wd3.myworkdayjobs.com/Airbus/job/Manching/Title_JR123
        String baseUrl = String.format(baseUrlTemplate, tenant, shardId);
        String jobUrl = baseUrl + "/" + site + externalId;

        try {
            String html = webClient.get()
                    .uri(URI.create(jobUrl))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (html == null || html.isBlank()) return null;

            // Extract description from JSON-LD schema.org JobPosting
            Document doc = Jsoup.parse(html);
            for (org.jsoup.nodes.Element script : doc.select("script[type='application/ld+json']")) {
                String json = script.html().trim();
                if (json.contains("\"description\"")) {
                    JsonNode root = objectMapper.readTree(json);
                    String desc = root.path("description").asText(null);
                    if (desc != null && !desc.isBlank()) {
                        return desc;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Workday fetchDescription [{}]: {}", jobUrl, e.getMessage());
            return null;
        }
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
}
