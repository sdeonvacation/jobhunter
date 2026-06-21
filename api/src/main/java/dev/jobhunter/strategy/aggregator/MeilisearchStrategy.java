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
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches jobs from Meilisearch-powered job boards via their public search API.
 * Expects config keys: url (search endpoint), apiKey (search-only key),
 * index (index name), filter (optional Meilisearch filter string).
 */
@Slf4j
@Component
public class MeilisearchStrategy implements FetchStrategy {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_OFFSET = 500;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public MeilisearchStrategy(WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "meilisearch";
    }

    @Override
    public boolean supports(AtsType type) {
        return false; // aggregator-only, not tied to an ATS type
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();

        String baseUrl = (String) context.config().get("url");
        String apiKey = (String) context.config().get("apiKey");
        String index = (String) context.config().get("index");
        String filter = (String) context.config().get("filter");

        if (baseUrl == null || apiKey == null || index == null) {
            return FetchResult.error("Meilisearch config requires url, apiKey, index", elapsed(start));
        }

        String searchUrl = baseUrl.replaceAll("/+$", "") + "/indexes/" + index + "/search";

        try {
            List<RawAggregatorJob> allJobs = new ArrayList<>();
            int offset = 0;

            while (offset < MAX_OFFSET && allJobs.size() < context.maxResults()) {
                String body = buildRequestBody("", filter, offset, PAGE_SIZE);

                log.debug("Meilisearch: fetching offset={} from {}", offset, searchUrl);

                String responseBody = webClient.post()
                        .uri(searchUrl)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(30));

                if (responseBody == null || responseBody.isBlank()) {
                    break;
                }

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode hits = root.path("hits");
                if (!hits.isArray() || hits.isEmpty()) {
                    break;
                }

                for (JsonNode hit : hits) {
                    RawAggregatorJob job = mapHit(hit);
                    if (job != null) {
                        allJobs.add(job);
                    }
                }

                // Stop if we got fewer than PAGE_SIZE (last page)
                if (hits.size() < PAGE_SIZE) {
                    break;
                }
                offset += PAGE_SIZE;
            }

            if (allJobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> limited = allJobs.size() > context.maxResults()
                    ? allJobs.subList(0, context.maxResults())
                    : allJobs;

            log.info("Meilisearch fetched {} jobs from {} (index={})", limited.size(), baseUrl, index);
            return FetchResult.success(limited, elapsed(start));

        } catch (Exception e) {
            log.error("Meilisearch fetch failed for {}: {}", searchUrl, e.getMessage());
            return FetchResult.error("Meilisearch fetch failed: " + e.getMessage(), elapsed(start));
        }
    }

    private String buildRequestBody(String query, String filter, int offset, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"q\":\"").append(query).append("\",\"limit\":").append(limit)
          .append(",\"offset\":").append(offset);
        if (filter != null && !filter.isBlank()) {
            sb.append(",\"filter\":\"").append(filter.replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private RawAggregatorJob mapHit(JsonNode hit) {
        String title = textOrNull(hit, "title");
        if (title == null) return null;

        String companyName = textOrNull(hit, "employer_name");
        String location = textOrNull(hit, "location.lvl1");
        if (location == null) {
            location = textOrNull(hit, "location.lvl0");
        }
        String description = textOrNull(hit, "description");
        String applyUrl = textOrNull(hit, "url");
        String externalId = textOrNull(hit, "id");

        // Published is epoch seconds in this API
        LocalDate postedDate = null;
        JsonNode publishedNode = hit.path("published");
        if (!publishedNode.isMissingNode() && publishedNode.isNumber()) {
            postedDate = Instant.ofEpochSecond(publishedNode.asLong())
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate();
        }

        return new RawAggregatorJob(
                externalId,
                title,
                companyName,
                location,
                description,
                applyUrl,
                postedDate,
                null, null, null,
                hit.toString()
        );
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
