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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches jobs from the WorkInFinland public board API.
 * Endpoint: GET {url}?limit={limit}&page={page}[&category={slug}]
 * The list API exposes no id/description/posted date/salary; externalUrl is the
 * only stable unique key and also the apply URL.
 */
@Slf4j
@Component
public class WorkInFinlandStrategy implements FetchStrategy {

    private static final int DEFAULT_LIMIT = 50;
    private static final long DEFAULT_DELAY_MS = 0L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final int EXTERNAL_ID_MAX_LEN = 255;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WorkInFinlandStrategy(WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "workinfinland";
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of();
    }

    @Override
    @Deprecated
    public boolean supports(AtsType type) {
        return false;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();

        String url = (String) context.config().get("url");
        if (url == null || url.isBlank()) {
            return FetchResult.error("WorkInFinland config requires url", elapsed(start));
        }

        String categoriesRaw = (String) context.config().get("categories");
        List<String> categories = (categoriesRaw == null || categoriesRaw.isBlank())
                ? List.of()
                : Arrays.stream(categoriesRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();

        int limit = parseIntConfig(context, "limit", DEFAULT_LIMIT);
        long delayMs = parseLongConfig(context, "delayBetweenPagesMs", DEFAULT_DELAY_MS);

        // One pass per category, or a single unfiltered pass when no categories configured.
        List<String> passes = categories.isEmpty() ? Collections.singletonList(null) : categories;

        Map<String, JsonNode> seen = new LinkedHashMap<>();

        try {
            for (String category : passes) {
                if (seen.size() >= context.maxResults()) {
                    break;
                }
                fetchCategory(url, category, limit, delayMs, context.maxResults(), seen);
            }
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                return FetchResult.rateLimited(elapsed(start));
            }
            List<RawAggregatorJob> partial = toJobs(seen);
            if (!partial.isEmpty()) {
                return FetchResult.success(partial, elapsed(start));
            }
            return FetchResult.error("WorkInFinland fetch failed: " + e.getMessage(), elapsed(start));
        } catch (Exception e) {
            log.error("WorkInFinland fetch failed for {}: {}", url, e.getMessage());
            List<RawAggregatorJob> partial = toJobs(seen);
            if (!partial.isEmpty()) {
                return FetchResult.success(partial, elapsed(start));
            }
            return FetchResult.error("WorkInFinland fetch failed: " + e.getMessage(), elapsed(start));
        }

        List<RawAggregatorJob> jobs = toJobs(seen);
        if (jobs.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }
        return FetchResult.success(jobs, elapsed(start));
    }

    private void fetchCategory(String baseUrl, String category, int limit, long delayMs,
                               int maxResults, Map<String, JsonNode> seen) throws Exception {
        int page = 1;
        int totalPages = Integer.MAX_VALUE;

        while (page <= totalPages && seen.size() < maxResults) {
            String requestUrl = buildUrl(baseUrl, category, limit, page);
            log.debug("WorkInFinland: category={}, page={}, url={}", category, page, requestUrl);

            String responseBody = webClient.get()
                    .uri(requestUrl)
                    .header("User-Agent", USER_AGENT)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (responseBody == null || responseBody.isBlank()) {
                break;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode jobsNode = root.path("jobs");
            if (!jobsNode.isArray()) {
                break;
            }
            if (jobsNode.isEmpty()) {
                break; // stop early on empty jobs array
            }

            totalPages = root.path("totalPages").asInt(1);

            int before = seen.size();
            for (JsonNode job : jobsNode) {
                JsonNode extUrlNode = job.path("externalUrl");
                if (extUrlNode.isMissingNode() || extUrlNode.isNull()
                        || extUrlNode.asText().isBlank()) {
                    continue;
                }
                seen.putIfAbsent(extUrlNode.asText(), job);
            }

            // Guard against fully-overlapping pages causing an infinite loop.
            if (seen.size() == before) {
                break;
            }

            if (page >= totalPages) {
                break;
            }
            page++;
            if (page <= totalPages && seen.size() < maxResults && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private String buildUrl(String baseUrl, String category, int limit, int page) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append("?limit=").append(limit).append("&page=").append(page);
        if (category != null && !category.isBlank()) {
            sb.append("&category=").append(category);
        }
        return sb.toString();
    }

    private List<RawAggregatorJob> toJobs(Map<String, JsonNode> seen) {
        List<RawAggregatorJob> jobs = new ArrayList<>();
        for (JsonNode node : seen.values()) {
            RawAggregatorJob job = mapJob(node);
            if (job != null) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private RawAggregatorJob mapJob(JsonNode node) {
        String title = textOrNull(node.path("title"));
        if (title == null) {
            return null;
        }

        String applyUrl = textOrNull(node.path("externalUrl"));
        if (applyUrl == null) {
            return null;
        }

        String companyName = textOrNull(node.path("employer").path("name"));
        String location = textOrNull(node.path("employer").path("city"));
        String externalId = deriveExternalId(applyUrl);

        return new RawAggregatorJob(
                externalId,
                title,
                companyName,
                location,
                null,        // description not available on list API
                applyUrl,
                null,        // postedDate not available on list API
                null, null, null, // salary not available on list API
                node.toString()
        );
    }

    private String deriveExternalId(String applyUrl) {
        if (applyUrl.length() <= EXTERNAL_ID_MAX_LEN) {
            return applyUrl;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(applyUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is part of every JVM; fall back to the raw URL if unavailable.
            return applyUrl;
        }
    }

    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    private int parseIntConfig(FetchContext context, String key, int defaultVal) {
        Object val = context.config().get(key);
        if (val == null) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private long parseLongConfig(FetchContext context, String key, long defaultVal) {
        Object val = context.config().get(key);
        if (val == null) {
            return defaultVal;
        }
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
