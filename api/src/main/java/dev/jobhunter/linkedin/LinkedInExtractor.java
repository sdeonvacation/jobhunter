package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInExtractor implements FetchStrategy {

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;

    public LinkedInExtractor(HttpMcpClient httpMcpClient, LinkedInRateLimiter rateLimiter) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.LINKEDIN;
    }

    @Override
    public String name() {
        return "linkedin";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();

        if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
            log.warn("LinkedIn rate limit reached for SEARCH, skipping extraction for endpoint {}", endpoint.getId());
            return FetchResult.error("Rate limit reached", elapsed(start));
        }

        try {
            String searchTerm = endpoint.getAtsSlug() != null
                    ? endpoint.getAtsSlug()
                    : endpoint.getCompany().getName();

            Map<String, Object> params = Map.of(
                    "keywords", searchTerm,
                    "company", endpoint.getCompany().getName()
            );

            JsonNode response = httpMcpClient.callTool("search_jobs", params);
            List<RawAggregatorJob> jobs = parseJobResults(response);

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("LinkedIn [{}]: extracted {} jobs", endpoint.getCompany().getName(), jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (McpClientException e) {
            log.error("LinkedIn extraction failed for {}: {}", endpoint.getCompany().getName(), e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        } catch (Exception e) {
            log.error("LinkedIn extraction unexpected error for {}: {}", endpoint.getCompany().getName(), e.getMessage());
            return FetchResult.error("Unexpected error: " + e.getMessage(), elapsed(start));
        }
    }

    private List<RawAggregatorJob> parseJobResults(JsonNode response) {
        List<RawAggregatorJob> jobs = new ArrayList<>();

        JsonNode jobsNode = response.path("jobs");
        if (!jobsNode.isArray()) {
            jobsNode = response.isArray() ? response : response.path("results");
        }

        if (!jobsNode.isArray()) {
            return jobs;
        }

        for (JsonNode jobNode : jobsNode) {
            RawAggregatorJob job = mapToRawAggregatorJob(jobNode);
            if (job != null) {
                jobs.add(job);
            }
        }

        return jobs;
    }

    private RawAggregatorJob mapToRawAggregatorJob(JsonNode node) {
        String title = getTextOrNull(node, "title", "job_title");
        if (title == null || title.isBlank()) {
            return null;
        }

        String externalId = getTextOrNull(node, "id", "job_id");
        if (externalId == null) {
            String url = getTextOrNull(node, "url", "job_url", "link");
            externalId = url != null ? String.valueOf(url.hashCode()) : UUID.randomUUID().toString();
        }

        String location = getTextOrNull(node, "location");
        String description = getTextOrNull(node, "description", "summary");
        String applyUrl = getTextOrNull(node, "url", "job_url", "link", "apply_url");
        String rawJson = node.toString();

        BigDecimal salaryMin = getDecimalOrNull(node, "salary_min", "min_salary");
        BigDecimal salaryMax = getDecimalOrNull(node, "salary_max", "max_salary");
        String salaryCurrency = getTextOrNull(node, "salary_currency", "currency");
        LocalDate postedDate = parseDateOrNull(node, "posted_date", "date_posted", "listed_at");

        return new RawAggregatorJob(
                externalId, title, null, location, description, applyUrl,
                postedDate, salaryMin, salaryMax, salaryCurrency, rawJson
        );
    }

    private String getTextOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private BigDecimal getDecimalOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return BigDecimal.valueOf(value.asDouble());
            }
        }
        return null;
    }

    private LocalDate parseDateOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return LocalDate.parse(value.asText(), DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    // Try alternative format
                    try {
                        return LocalDate.parse(value.asText(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    } catch (DateTimeParseException ignored) {
                        // Skip unparseable dates
                    }
                }
            }
        }
        return null;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
