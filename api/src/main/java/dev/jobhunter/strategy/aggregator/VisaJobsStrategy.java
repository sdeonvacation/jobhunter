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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches jobs from the VisaJobs Supabase REST list endpoint.
 * Endpoint: GET {url}?select=*&hidden=eq.false[&visa_confirmed=eq.Yes]
 *   &order=posted.desc.nullslast,job_id.desc&offset={offset}&limit={limit}
 * Every list request carries the anon key (apikey header) plus a short-lived
 * access token (Authorization: Bearer) obtained from the Supabase auth
 * refresh-token flow via {@link VisaJobsTokenProvider}.
 */
@Slf4j
@Component
public class VisaJobsStrategy implements FetchStrategy {

    private static final int DEFAULT_LIMIT = 100;
    private static final long DEFAULT_DELAY_MS = 0L;
    private static final int EXTERNAL_ID_MAX_LEN = 255;

    private final WebClient webClient;
    private final VisaJobsTokenProvider tokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisaJobsStrategy(WebClient webClient, VisaJobsTokenProvider tokenProvider) {
        this.webClient = webClient;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public String name() {
        return "visajobs";
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
        String apikey = (String) context.config().get("apikey");
        String authUrl = (String) context.config().get("auth-url");
        if (url == null || url.isBlank() || apikey == null || apikey.isBlank()
                || authUrl == null || authUrl.isBlank()) {
            return FetchResult.error("VisaJobs config requires url, apikey, auth-url", elapsed(start));
        }

        int limit = parseIntConfig(context, "limit", DEFAULT_LIMIT);
        boolean visaOnly = parseBoolConfig(context, "visa-confirmed-only", true);
        long delayMs = parseLongConfig(context, "delay-between-pages-ms", DEFAULT_DELAY_MS);

        Map<String, JsonNode> seen = new LinkedHashMap<>();

        try {
            int offset = 0;
            while (seen.size() < context.maxResults()) {
                String token = tokenProvider.getAccessToken(authUrl, apikey);

                List<JsonNode> rows;
                try {
                    rows = fetchPage(url, apikey, offset, limit, visaOnly, token);
                } catch (WebClientResponseException e) {
                    if (e.getStatusCode().value() == 401) {
                        tokenProvider.invalidate();
                        String freshToken = tokenProvider.getAccessToken(authUrl, apikey);
                        rows = fetchPage(url, apikey, offset, limit, visaOnly, freshToken);
                    } else if (e.getStatusCode().value() == 429) {
                        return FetchResult.rateLimited(elapsed(start));
                    } else {
                        throw e;
                    }
                }

                if (rows.isEmpty()) {
                    break;
                }

                int before = seen.size();
                for (JsonNode row : rows) {
                    seen.putIfAbsent(textOrNull(row.path("job_id")), row);
                }
                // Guard against fully-overlapping pages causing an infinite loop.
                if (seen.size() == before) {
                    break;
                }

                if (rows.size() < limit) {
                    break; // last page
                }

                offset += limit;
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (VisaJobsRefreshTokenException e) {
            log.warn("VisaJobs: token refresh failed: {}", e.getMessage());
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                return FetchResult.protectedEndpoint(elapsed(start));
            }
            if (e.getStatusCode().value() == 429) {
                return FetchResult.rateLimited(elapsed(start));
            }
            List<RawAggregatorJob> partial = toJobs(seen);
            if (!partial.isEmpty()) {
                return FetchResult.success(partial, elapsed(start));
            }
            return FetchResult.error("VisaJobs fetch failed: " + e.getMessage(), elapsed(start));
        } catch (Exception e) {
            log.error("VisaJobs fetch failed for {}: {}", url, e.getMessage());
            List<RawAggregatorJob> partial = toJobs(seen);
            if (!partial.isEmpty()) {
                return FetchResult.success(partial, elapsed(start));
            }
            return FetchResult.error("VisaJobs fetch failed: " + e.getMessage(), elapsed(start));
        }

        List<RawAggregatorJob> jobs = toJobs(seen);
        if (jobs.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }
        return FetchResult.success(jobs, elapsed(start));
    }

    private List<JsonNode> fetchPage(String url, String apikey, int offset, int limit,
                                     boolean visaOnly, String token) throws Exception {
        String requestUrl = buildListUrl(url, offset, limit, visaOnly);
        log.debug("VisaJobs: offset={}, limit={}, url={}", offset, limit, requestUrl);

        String responseBody = webClient.get()
                .uri(requestUrl)
                .header("apikey", apikey)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(responseBody);
        if (!root.isArray()) {
            return List.of();
        }
        List<JsonNode> rows = new ArrayList<>();
        root.forEach(rows::add);
        return rows;
    }

    private String buildListUrl(String baseUrl, int offset, int limit, boolean visaOnly) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append("?select=*&hidden=eq.false");
        if (visaOnly) {
            sb.append("&visa_confirmed=eq.Yes");
        }
        sb.append("&order=posted.desc.nullslast,job_id.desc");
        sb.append("&offset=").append(offset).append("&limit=").append(limit);
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

        String applyUrl = textOrNull(node.path("url"));
        if (applyUrl == null) {
            return null;
        }

        String jobId = textOrNull(node.path("job_id"));
        if (jobId == null) {
            return null;
        }

        String externalId = deriveExternalId(jobId);
        String companyName = textOrNull(node.path("company"));
        String location = textOrNull(node.path("location"));
        String description = textOrNull(node.path("description_text"));
        LocalDate postedDate = parsePostedDate(node.path("posted"));

        return new RawAggregatorJob(
                externalId,
                title,
                companyName,
                location,
                description,
                applyUrl,
                postedDate,
                null, null, null, // salary not available on list API
                node.toString()
        );
    }

    private LocalDate parsePostedDate(JsonNode postedNode) {
        if (postedNode.isMissingNode() || postedNode.isNull()) {
            return null;
        }
        String raw = postedNode.asText();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String deriveExternalId(String jobId) {
        if (jobId.length() <= EXTERNAL_ID_MAX_LEN) {
            return jobId;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(jobId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is part of every JVM; fall back to the raw id if unavailable.
            return jobId;
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

    private boolean parseBoolConfig(FetchContext context, String key, boolean defaultVal) {
        Object val = context.config().get(key);
        if (val == null) {
            return defaultVal;
        }
        return Boolean.parseBoolean(val.toString().trim());
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}