package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class RestApiStrategy implements FetchStrategy {
    private static final int MAX_PAGES = 5;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RestApiStrategy(WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "rest-api";
    }

    @Override
    public boolean supports(AtsType type) {
        return false;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String url = (String) context.config().get("url");

        if (url == null || url.isBlank()) {
            return FetchResult.error("No URL configured in context", elapsed(start));
        }

        try {
            List<RawAggregatorJob> allJobs = new ArrayList<>();
            String nextUrl = url;
            int page = 0;

            while (nextUrl != null && page < MAX_PAGES && allJobs.size() < context.maxResults()) {
                log.debug("Fetching REST API page {} from {}", page + 1, nextUrl);

                String responseBody = webClient.get()
                        .uri(nextUrl)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(30));

                if (responseBody == null || responseBody.isBlank()) {
                    break;
                }

                JsonNode root = objectMapper.readTree(responseBody);
                List<RawAggregatorJob> pageJobs = parseJobs(root);
                allJobs.addAll(pageJobs);

                nextUrl = extractNextPageUrl(root);
                page++;
            }

            if (allJobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> limited = allJobs.size() > context.maxResults()
                    ? allJobs.subList(0, context.maxResults())
                    : allJobs;

            log.info("REST API fetched {} jobs from {} ({} pages)", limited.size(), url, page);
            return FetchResult.success(limited, elapsed(start));

        } catch (Exception e) {
            log.error("REST API fetch failed for {}: {}", url, e.getMessage());
            return FetchResult.error("REST API fetch failed: " + e.getMessage(), elapsed(start));
        }
    }

    private List<RawAggregatorJob> parseJobs(JsonNode root) {
        List<RawAggregatorJob> jobs = new ArrayList<>();

        // Support both root array and "data" wrapper
        JsonNode jobArray = root.isArray() ? root : root.path("data");
        if (!jobArray.isArray()) {
            return jobs;
        }

        for (JsonNode node : jobArray) {
            RawAggregatorJob job = parseJobNode(node);
            if (job != null) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private RawAggregatorJob parseJobNode(JsonNode node) {
        String title = textOrNull(node, "title");
        if (title == null) {
            return null;
        }

        String companyName = textOrNull(node, "company_name");
        String location = textOrNull(node, "location");
        String description = textOrNull(node, "description");
        String applyUrl = textOrNull(node, "url");
        String slug = textOrNull(node, "slug");
        String createdAt = textOrNull(node, "created_at");

        // Use slug as external ID if available
        String externalId = slug != null ? slug : textOrNull(node, "id");

        LocalDate postedDate = parseDate(createdAt);

        return new RawAggregatorJob(
                externalId,
                title,
                companyName,
                location,
                description,
                applyUrl,
                postedDate,
                null, null, null,
                node.toString()
        );
    }

    private String extractNextPageUrl(JsonNode root) {
        // Check for links.next pattern
        JsonNode links = root.path("links");
        if (!links.isMissingNode()) {
            JsonNode next = links.path("next");
            if (!next.isMissingNode() && !next.isNull() && !next.asText().isBlank()) {
                return next.asText();
            }
        }

        // Check for meta.next pattern
        JsonNode meta = root.path("meta");
        if (!meta.isMissingNode()) {
            JsonNode next = meta.path("next");
            if (!next.isMissingNode() && !next.isNull() && !next.asText().isBlank()) {
                return next.asText();
            }
        }

        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null) {
            return null;
        }
        try {
            // Try ISO date-time first (2024-01-15T10:30:00)
            if (dateStr.contains("T")) {
                return LocalDate.parse(dateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            log.trace("Could not parse date: {}", dateStr);
            return null;
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
