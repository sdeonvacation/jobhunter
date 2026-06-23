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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches jobs from BuiltInEurope via their public search API.
 * Expects config key: url (POST search endpoint).
 * Fan-out by keyword; deduplicates across keywords by job id.
 */
@Slf4j
@Component
public class BuiltInEuropeStrategy implements FetchStrategy {

    private static final int PAGE_SIZE = 100;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public BuiltInEuropeStrategy(WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "builtineurope";
    }

    @Override
    public boolean supports(AtsType type) {
        return false; // aggregator-only, not tied to an ATS type
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();

        String apiUrl = (String) context.config().get("url");
        if (apiUrl == null || apiUrl.isBlank()) {
            return FetchResult.error("BuiltInEurope config requires url", elapsed(start));
        }

        List<String> keywords = context.keywords();
        List<String> effectiveKeywords = (keywords == null || keywords.isEmpty())
                ? List.of("")
                : keywords;

        try {
            // Dedup across keyword fan-out by job id
            Map<String, JsonNode> seen = new LinkedHashMap<>();

            for (String keyword : effectiveKeywords) {
                if (seen.size() >= context.maxResults()) break;
                fetchKeyword(apiUrl, keyword, context.maxResults(), seen);
            }

            if (seen.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> allJobs = new ArrayList<>();
            for (JsonNode node : seen.values()) {
                RawAggregatorJob job = mapJob(node);
                if (job != null) {
                    allJobs.add(job);
                }
            }

            if (allJobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> limited = allJobs.size() > context.maxResults()
                    ? allJobs.subList(0, context.maxResults())
                    : allJobs;

            log.info("BuiltInEurope fetched {} jobs from {}", limited.size(), apiUrl);
            return FetchResult.success(limited, elapsed(start));

        } catch (Exception e) {
            log.error("BuiltInEurope fetch failed for {}: {}", apiUrl, e.getMessage());
            return FetchResult.error("BuiltInEurope fetch failed: " + e.getMessage(), elapsed(start));
        }
    }

    private void fetchKeyword(String apiUrl, String keyword, int maxResults, Map<String, JsonNode> seen)
            throws Exception {
        int page = 1;

        while (seen.size() < maxResults) {
            String body = buildRequestBody(keyword, page);

            log.debug("BuiltInEurope: keyword='{}' page={} url={}", keyword, page, apiUrl);

            String responseBody = webClient.post()
                    .uri(apiUrl)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (responseBody == null || responseBody.isBlank()) {
                break;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray() || jobs.isEmpty()) {
                break;
            }

            for (JsonNode job : jobs) {
                JsonNode idNode = job.path("id");
                if (!idNode.isMissingNode() && !idNode.isNull()) {
                    seen.putIfAbsent(idNode.asText(), job);
                }
            }

            if (jobs.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
    }

    private String buildRequestBody(String query, int page) {
        String escaped = query.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"query\":\"" + escaped + "\",\"page\":" + page + ",\"per_page\":" + PAGE_SIZE + "}";
    }

    private RawAggregatorJob mapJob(JsonNode node) {
        String title = textOrNull(node, "title_raw");
        if (title == null) return null;

        String externalId = textOrNull(node, "id");
        String companyName = textOrNull(node, "company_display_name");
        String location = textOrNull(node, "location_name");
        String applyUrl = textOrNull(node, "posting_url");

        // skills array → join as description
        String description = null;
        JsonNode skillsNode = node.path("skills");
        if (skillsNode.isArray() && !skillsNode.isEmpty()) {
            List<String> skills = new ArrayList<>();
            for (JsonNode s : skillsNode) {
                if (!s.isNull() && !s.asText().isBlank()) {
                    skills.add(s.asText());
                }
            }
            if (!skills.isEmpty()) {
                description = String.join(", ", skills);
            }
        }

        // first_seen epoch seconds → LocalDate
        LocalDate postedDate = null;
        JsonNode firstSeen = node.path("first_seen");
        if (!firstSeen.isMissingNode() && !firstSeen.isNull() && firstSeen.isNumber()) {
            postedDate = Instant.ofEpochSecond(firstSeen.asLong())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
        }

        BigDecimal salaryMin = decimalOrNull(node, "salary_min");
        BigDecimal salaryMax = decimalOrNull(node, "salary_max");
        String salaryCurrency = textOrNull(node, "salary_currency");

        return new RawAggregatorJob(
                externalId,
                title,
                companyName,
                location,
                description,
                applyUrl,
                postedDate,
                salaryMin,
                salaryMax,
                salaryCurrency,
                node.toString()
        );
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
