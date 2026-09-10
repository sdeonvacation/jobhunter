package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches jobs from the authenticated Inertia board
 * {@code https://globalmove.relocate.me/jobs}.
 *
 * <p>Each request carries the subscriber's {@code the-global-move-session}
 * cookie in a per-request header (never a global WebClient default header, so
 * the shared client cannot leak the credential to other sources). The response
 * is server-rendered HTML embedding an Inertia JSON payload in
 * {@code <script data-page="app" type="application/json">}; the strategy reads
 * {@code props.jobs} (a Laravel paginator) and walks pages via
 * {@code next_page_url} until it is null or {@code maxResults} is reached.
 *
 * <p>A dead session (login payload, HTTP 401/403) surfaces as
 * {@link FetchResult#protectedEndpoint} and marks the session dead so the
 * operator re-runs {@code scripts/globalmove-login.sh}.
 */
@Slf4j
@Component
public class GlobalMoveStrategy implements FetchStrategy {

    private static final long DEFAULT_DELAY_MS = 0L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern RELATIVE_DATE_PATTERN =
            Pattern.compile("^(\\d+)\\s*d(ay)?s?\\s*ago$");

    private final WebClient webClient;
    private final GlobalMoveSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlobalMoveStrategy(WebClient webClient, GlobalMoveSessionManager sessionManager) {
        this.webClient = webClient;
        this.sessionManager = sessionManager;
    }

    @Override
    public String name() {
        return "globalmove";
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

        Map<String, Object> config = context.config();
        String url = config == null ? null : (String) config.get("url");
        if (url == null || url.isBlank()) {
            return FetchResult.error("GlobalMove config requires url", elapsed(start));
        }

        long delayMs = parseLongConfig(context, "delay-between-pages-ms", DEFAULT_DELAY_MS);
        int maxResults = context.maxResults();

        if (!sessionManager.isConfigured() || !sessionManager.isValid()) {
            log.warn("GlobalMove: session unconfigured or dead; endpoint requires authentication");
            return FetchResult.protectedEndpoint(elapsed(start));
        }

        String cookie;
        try {
            cookie = sessionManager.getSessionCookie();
        } catch (GlobalMoveSessionException e) {
            log.warn("GlobalMove: session unavailable: {}", e.getMessage());
            return FetchResult.protectedEndpoint(elapsed(start));
        }

        List<RawAggregatorJob> jobs = new ArrayList<>();
        try {
            int page = 1;
            JsonNode paginator = fetchPage(url, page, cookie);

            while (true) {
                collect(paginator, jobs, maxResults);

                String nextPageUrl = textOrNull(paginator.path("next_page_url"));
                if (nextPageUrl == null || jobs.size() >= maxResults) {
                    break;
                }

                int nextPage = derivePageNumber(nextPageUrl, page);
                if (nextPage <= page) {
                    nextPage = page + 1;
                }
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                page = nextPage;
                paginator = fetchPage(url, page, cookie);
            }
        } catch (GlobalMoveSessionException e) {
            log.warn("GlobalMove: session rejected for {}: {}", url, e.getMessage());
            sessionManager.markDead();
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                log.warn("GlobalMove: HTTP {} for {}; marking session dead", status, url);
                sessionManager.markDead();
                return FetchResult.protectedEndpoint(elapsed(start));
            }
            if (status == 429) {
                return FetchResult.rateLimited(elapsed(start));
            }
            if (!jobs.isEmpty()) {
                log.warn("GlobalMove: page fetch failed (HTTP {}) after {} jobs; returning partial",
                        status, jobs.size());
                return FetchResult.success(jobs, elapsed(start));
            }
            return FetchResult.error("GlobalMove fetch failed: " + e.getMessage(), elapsed(start));
        } catch (RuntimeException e) {
            if (!jobs.isEmpty()) {
                log.warn("GlobalMove: page fetch failed after {} jobs; returning partial: {}",
                        jobs.size(), e.getMessage());
                return FetchResult.success(jobs, elapsed(start));
            }
            log.warn("GlobalMove: fetch failed for {}: {}", url, e.getMessage());
            return FetchResult.error("GlobalMove fetch failed: " + e.getMessage(), elapsed(start));
        }

        if (jobs.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }
        return FetchResult.success(jobs, elapsed(start));
    }

    /**
     * Fetches one page and returns the {@code props.jobs} paginator node.
     *
     * @throws GlobalMoveSessionException when the response is a login page (dead session)
     * @throws IllegalStateException      when the Inertia payload is missing or malformed
     */
    private JsonNode fetchPage(String url, int page, String cookie) {
        String requestUrl = buildPageUrl(url, page);
        log.debug("GlobalMove: page={}, url={}", page, requestUrl);

        String responseBody = webClient.get()
                .uri(requestUrl)
                .header(HttpHeaders.COOKIE,
                        GlobalMoveSessionManager.SESSION_COOKIE_NAME + "=" + cookie)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("GlobalMove parse failed: empty response body");
        }

        Element script = Jsoup.parse(responseBody).selectFirst("script[data-page=app]");
        if (script == null) {
            throw new IllegalStateException(
                    "GlobalMove parse failed: no Inertia data-page=app script");
        }

        String json = script.data();
        if (json == null || json.isBlank()) {
            json = script.html();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GlobalMove parse failed: malformed Inertia JSON", e);
        }

        JsonNode jobs = root.path("props").path("jobs");
        if (!jobs.isObject()) {
            String component = root.path("component").asText("");
            if (component.toLowerCase(Locale.ROOT).contains("login")) {
                throw new GlobalMoveSessionException(
                        "GlobalMove returned login page; session is dead");
            }
            throw new IllegalStateException(
                    "GlobalMove parse failed: props.jobs paginator missing (component=" + component + ")");
        }
        return jobs;
    }

    private void collect(JsonNode paginator, List<RawAggregatorJob> jobs, int maxResults) {
        JsonNode data = paginator.path("data");
        if (!data.isArray()) {
            return;
        }
        for (JsonNode item : data) {
            if (jobs.size() >= maxResults) {
                break;
            }
            RawAggregatorJob job = mapJob(item);
            if (job != null) {
                jobs.add(job);
            }
        }
    }

    private RawAggregatorJob mapJob(JsonNode item) {
        JsonNode idNode = item.path("id");
        if (idNode.isMissingNode() || idNode.isNull()) {
            return null;
        }
        String id = idNode.asText();
        if (id == null || id.isBlank()) {
            return null;
        }

        String title = textOrNull(item.path("name"));
        if (title == null) {
            return null;
        }

        String applyUrl = textOrNull(item.path("apply_url"));
        if (applyUrl == null) {
            return null;
        }

        String companyName = textOrNull(item.path("company"));
        String location = joinArray(item.path("countries"));
        LocalDate postedDate = parsePostedDate(item.path("posted_date"));

        return new RawAggregatorJob(
                deriveExternalId(id),
                title,
                companyName,
                location,
                null,            // board list item carries no description
                applyUrl,
                postedDate,
                null, null, null, // salary not available on list item
                item.toString()
        );
    }

    private LocalDate parsePostedDate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("new".equals(value) || "today".equals(value)) {
            return LocalDate.now();
        }
        if ("yesterday".equals(value)) {
            return LocalDate.now().minusDays(1);
        }
        Matcher matcher = RELATIVE_DATE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.now().minusDays(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String joinArray(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode element : node) {
            String text = textOrNull(element);
            if (text == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(text);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String buildPageUrl(String baseUrl, int page) {
        if (page <= 1) {
            return baseUrl;
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "page=" + page;
    }

    private int derivePageNumber(String nextPageUrl, int currentPage) {
        try {
            URI uri = URI.create(nextPageUrl);
            String query = uri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    int eq = param.indexOf('=');
                    if (eq > 0 && "page".equals(param.substring(0, eq))) {
                        return Integer.parseInt(param.substring(eq + 1));
                    }
                }
            }
        } catch (RuntimeException e) {
            // fall through to simple increment
        }
        return currentPage + 1;
    }

    private String deriveExternalId(String id) {
        return "gm-" + id;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private long parseLongConfig(FetchContext context, String key, long defaultVal) {
        Map<String, Object> config = context.config();
        if (config == null) {
            return defaultVal;
        }
        Object val = config.get(key);
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
