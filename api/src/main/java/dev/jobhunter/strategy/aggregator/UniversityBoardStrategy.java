package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Template-method base for server-rendered university job boards.
 *
 * <p>The base owns the shared orchestration: paginated listing enumeration,
 * scope filtering, dedup by board id, per-run bounds, politeness delay, the
 * detail-fetch loop with fault tolerance, and the {@link FetchResult} outcome
 * mapping. A board subclass supplies only the board-specific hooks:
 * {@link #name()}, {@link #buildListingUrl(String, int)},
 * {@link #parseListingJobs(String)}, {@link #matchesScope(BoardJob)} (default
 * {@code true}), and {@link #parseDetail(String, BoardJob)}.
 */
@Slf4j
public abstract class UniversityBoardStrategy implements FetchStrategy {

    protected static final long DEFAULT_DELAY_MS = 1000L;
    protected static final int DEFAULT_MAX_SCRAPE_PER_RUN = 100;
    protected static final int DEFAULT_MAX_PAGES = 10;
    protected static final int DEFAULT_MAX_CONSECUTIVE_DETAIL_FAILURES = 5;
    protected static final int DEFAULT_TIMEOUT_SECONDS = 30;
    protected static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /**
     * Signals that a listing page did not contain the expected board payload (markup/API drift),
     * so the base surfaces the failure instead of silently reporting an empty run. An empty but
     * valid payload is not a failure.
     */
    static class ListingParseException extends RuntimeException {
        ListingParseException(String message) {
            super(message);
        }
    }

    protected final WebClient webClient;

    protected UniversityBoardStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public abstract String name();

    /** Listing URL for the given 1-based page. For wissenschaftsstellen only the page varies. */
    protected abstract String buildListingUrl(String baseUrl, int page);

    /** Parse the board-neutral listing records from one page. Malformed rows are skipped. */
    protected abstract List<BoardJob> parseListingJobs(String html);

    /** Scope predicate applied before dedup/bounds. Default keeps everything. */
    protected boolean matchesScope(BoardJob job) {
        return true;
    }

    /** Map a detail page (or {@code null} when no detail URL exists) to a full job. */
    protected abstract RawAggregatorJob parseDetail(String html, BoardJob job);

    @Override
    public final FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        Map<String, Object> config = context.config() == null ? Map.of() : context.config();

        String baseUrl = configString(config, "url");
        if (!present(baseUrl)) {
            return FetchResult.error("university board config requires url", elapsed(start));
        }

        long delayBetweenMs = longConfig(config, "delayBetweenMs", DEFAULT_DELAY_MS);
        int maxScrapePerRun = intConfig(config, "maxScrapePerRun", DEFAULT_MAX_SCRAPE_PER_RUN);
        // YamlSourceConfig.buildContext() hardcodes FetchContext.maxPages = 3, so the base
        // reads its pagination bound from the config map rather than context.maxPages().
        int maxPages = intConfig(config, "maxPages", DEFAULT_MAX_PAGES);
        int maxConsecutiveDetailFailures = intConfig(config, "maxConsecutiveDetailFailures",
                DEFAULT_MAX_CONSECUTIVE_DETAIL_FAILURES);

        boolean rateLimited = false;
        boolean transportFailure = false;
        boolean parseFailure = false;
        Map<String, BoardJob> candidates = new LinkedHashMap<>();
        // Raw ids seen across pages drive the exhaustion/overlap stop, independent of scope:
        // a full page of out-of-scope postings must not end enumeration.
        Set<String> seenExternalIds = new HashSet<>();

        // ── Enumerate listing pages, newest-first, stopping on an exhausted/overlapping page ──
        for (int page = 1; page <= maxPages; page++) {
            String listingUrl = buildListingUrl(baseUrl, page);
            String html;
            try {
                html = getHtml(listingUrl);
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 429) {
                    log.warn("[{}] Rate limited on listing page {}", name(), page);
                    rateLimited = true;
                    break;
                }
                log.warn("[{}] HTTP {} on listing page {}", name(), status, page);
                transportFailure = true;
                break;
            } catch (Exception e) {
                log.warn("[{}] Error fetching listing page {}: {}", name(), page, e.getMessage());
                transportFailure = true;
                break;
            }
            if (html == null || html.isBlank()) {
                break;
            }

            List<BoardJob> parsed;
            try {
                parsed = parseListingJobs(html);
            } catch (ListingParseException e) {
                // Expected payload missing/unparseable: stop paging to avoid hammering on drift.
                log.warn("[{}] Listing parse failure on page {}: {}", name(), page, e.getMessage());
                parseFailure = true;
                break;
            } catch (Exception e) {
                log.warn("[{}] Error parsing listing page {}: {}", name(), page, e.getMessage());
                transportFailure = true;
                break;
            }
            if (parsed == null || parsed.isEmpty()) {
                break;
            }

            int before = seenExternalIds.size();
            for (BoardJob job : parsed) {
                if (job == null) continue;
                if (!present(job.externalId())) continue;
                seenExternalIds.add(job.externalId());
                if (!matchesScope(job)) continue;
                candidates.putIfAbsent(job.externalId(), job);
            }
            if (seenExternalIds.size() == before) {
                break; // page yielded no new externalIds
            }
        }

        // ── Newest-first bounded window: first min(maxScrapePerRun, maxResults) candidates ──
        int bound = Math.max(0, Math.min(maxScrapePerRun, context.maxResults()));
        List<BoardJob> scoped = new ArrayList<>(candidates.values());
        if (scoped.size() > bound) {
            scoped = scoped.subList(0, bound);
        }

        // ── Detail loop with politeness delay and per-page fault tolerance ──
        List<RawAggregatorJob> jobs = new ArrayList<>();
        int consecutiveDetailFailures = 0;
        boolean anyFetch = false;
        for (BoardJob job : scoped) {
            String detailUrl = job.detailUrl();
            String html = null;
            if (present(detailUrl)) {
                if (anyFetch && delayBetweenMs > 0) {
                    try {
                        Thread.sleep(delayBetweenMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                anyFetch = true;
                try {
                    html = getHtml(detailUrl);
                } catch (WebClientResponseException e) {
                    int status = e.getStatusCode().value();
                    if (status == 429) {
                        log.warn("[{}] Rate limited on detail {}", name(), detailUrl);
                        rateLimited = true;
                        break;
                    }
                    if (status == 404 || status == 410) {
                        log.debug("[{}] Detail removed (HTTP {}) at {}", name(), status, detailUrl);
                        consecutiveDetailFailures = 0;
                        continue;
                    }
                    transportFailure = true;
                    consecutiveDetailFailures++;
                    log.warn("[{}] HTTP {} fetching detail {}", name(), status, detailUrl);
                    if (consecutiveDetailFailures >= maxConsecutiveDetailFailures) break;
                    continue;
                } catch (Exception e) {
                    transportFailure = true;
                    consecutiveDetailFailures++;
                    log.warn("[{}] Error fetching detail {}: {}", name(), detailUrl, e.getMessage());
                    if (consecutiveDetailFailures >= maxConsecutiveDetailFailures) break;
                    continue;
                }
                consecutiveDetailFailures = 0;
            }

            // No detail URL (or the listing had none): parseDetail(null, job) lets the board
            // still emit from listing fields; otherwise the detail HTML is mapped.
            try {
                RawAggregatorJob mapped = parseDetail(html, job);
                if (mapped != null) {
                    jobs.add(mapped);
                }
            } catch (Exception e) {
                log.warn("[{}] Error parsing detail for {}: {}", name(), job.externalId(), e.getMessage());
            }
        }

        if (rateLimited) {
            return FetchResult.rateLimited(elapsed(start));
        }
        boolean failed = transportFailure || parseFailure;
        if (failed && !jobs.isEmpty()) {
            return FetchResult.success(jobs, elapsed(start));
        }
        if (failed) {
            return FetchResult.error(name() + " fetch failed", elapsed(start));
        }
        if (jobs.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }
        return FetchResult.success(jobs, elapsed(start));
    }

    protected String getHtml(String url) {
        return getHtml(url, DEFAULT_TIMEOUT_SECONDS);
    }

    protected String getHtml(String url, int timeoutSeconds) {
        return webClient.get()
                .uri(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    protected int intConfig(Map<String, Object> config, String key, int defaultValue) {
        String value = configString(config, key);
        if (!present(value)) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected long longConfig(Map<String, Object> config, String key, long defaultValue) {
        String value = configString(config, key);
        if (!present(value)) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected String configString(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : value.toString();
    }

    protected boolean present(String value) {
        return value != null && !value.isBlank();
    }

    protected Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
