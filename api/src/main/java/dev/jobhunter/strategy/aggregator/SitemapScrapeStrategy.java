package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
public abstract class SitemapScrapeStrategy implements FetchStrategy {

    protected final WebClient webClient;
    protected final JobPostingRepository jobPostingRepository;

    protected SitemapScrapeStrategy(WebClient webClient, JobPostingRepository jobPostingRepository) {
        this.webClient = webClient;
        this.jobPostingRepository = jobPostingRepository;
    }

    /** Strategy name used by StrategyRegistry and YAML config. */
    @Override
    public abstract String name();

    /** Regex pattern to filter sitemap &lt;loc&gt; URLs. */
    protected abstract Pattern urlFilterPattern();

    /** Extract stable externalId from a URL. */
    protected abstract String extractExternalId(String url);

    /** Parse a single detail page into a RawAggregatorJob. Return empty to skip. */
    protected abstract Optional<RawAggregatorJob> parsePage(String html, String url, String externalId);

    /** JobSource enum value used for incremental dedup queries. */
    protected abstract JobSource jobSource();

    protected int defaultDelayMs() { return 400; }
    protected int defaultMaxScrapePerRun() { return 50; }
    protected int sitemapTimeoutSeconds() { return 30; }
    protected int pageTimeoutSeconds() { return 15; }

    @Override
    public final boolean supports(AtsType type) { return false; }

    @Override
    public final FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();

        String sitemapUrl = resolveUrl(context);
        if (sitemapUrl == null || sitemapUrl.isBlank()) {
            return FetchResult.error("No URL configured in context", elapsed(start));
        }

        int delayMs = parseIntConfig(context, "delayBetweenMs", defaultDelayMs());
        int maxScrape = parseIntConfig(context, "maxScrapePerRun", defaultMaxScrapePerRun());

        // Phase 1: Fetch + parse sitemap
        List<SitemapXmlParser.SitemapEntry> entries;
        try {
            String xml = webClient.get()
                    .uri(sitemapUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(sitemapTimeoutSeconds()));
            if (xml == null || xml.isBlank()) {
                return FetchResult.error("Empty sitemap response", elapsed(start));
            }
            entries = SitemapXmlParser.parse(xml);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("[{}] Rate limited fetching sitemap", name());
                return FetchResult.rateLimited(elapsed(start));
            }
            log.error("[{}] Sitemap fetch failed (HTTP {}): {}", name(), e.getStatusCode().value(), e.getMessage());
            return FetchResult.error("Sitemap fetch failed: " + e.getMessage(), elapsed(start));
        } catch (Exception e) {
            log.error("[{}] Sitemap fetch failed: {}", name(), e.getMessage());
            return FetchResult.error("Sitemap fetch failed: " + e.getMessage(), elapsed(start));
        }

        log.debug("[{}] Sitemap contains {} entries", name(), entries.size());

        // Phase 2: Filter URLs matching pattern
        Pattern pattern = urlFilterPattern();
        List<String> matchedUrls = entries.stream()
                .map(SitemapXmlParser.SitemapEntry::url)
                .filter(url -> pattern.matcher(url).matches())
                .toList();

        log.debug("[{}] {} URLs match filter pattern", name(), matchedUrls.size());

        // Phase 3: Load known externalIds for incremental skip
        Set<String> knownIds;
        try {
            knownIds = new HashSet<>(jobPostingRepository.findExternalIdsBySource(jobSource()));
        } catch (Exception e) {
            log.warn("[{}] Failed to load known IDs, proceeding without incremental dedup: {}", name(), e.getMessage());
            knownIds = new HashSet<>();
        }

        // Phase 4: Determine new URLs (not yet in DB), capped at maxScrapePerRun
        final Set<String> finalKnownIds = knownIds;
        List<String> candidateUrls = matchedUrls.stream()
                .filter(url -> !finalKnownIds.contains(extractExternalId(url)))
                .toList();
        List<String> newUrls = candidateUrls.stream().limit(maxScrape).toList();
        int skippedKnown = matchedUrls.size() - candidateUrls.size();
        int skippedCapped = candidateUrls.size() - newUrls.size();

        log.info("[{}] {} new URLs to scrape (known={}, capped={} by limit={})",
                name(), newUrls.size(), skippedKnown, skippedCapped, maxScrape);

        if (newUrls.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }

        // Phase 5: Scrape detail pages
        List<RawAggregatorJob> jobs = new ArrayList<>();
        int errors = 0;
        boolean rateLimited = false;

        for (int i = 0; i < newUrls.size(); i++) {
            String url = newUrls.get(i);
            try {
                String html = webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(pageTimeoutSeconds()));
                if (html == null || html.isBlank()) {
                    log.debug("[{}] Empty page at {}", name(), url);
                    continue;
                }
                String externalId = extractExternalId(url);
                parsePage(html, url, externalId).ifPresent(jobs::add);
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 429) {
                    log.warn("[{}] Rate limited at {} — stopping early with {} jobs so far", name(), url, jobs.size());
                    rateLimited = true;
                    break;
                }
                if (status == 404 || status == 410) {
                    log.debug("[{}] Job removed ({}) at {}", name(), status, url);
                    continue;
                }
                log.warn("[{}] HTTP {} fetching {}", name(), status, url);
                errors++;
            } catch (Exception e) {
                log.warn("[{}] Error fetching {}: {}", name(), url, e.getMessage());
                errors++;
            }

            // Configurable delay between fetches (skip after last page)
            if (delayMs > 0 && i < newUrls.size() - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (errors > 0) {
            log.warn("[{}] {} fetch errors in this run", name(), errors);
        }
        log.info("[{}] Scraped {} jobs (errors={}, rateLimited={})", name(), jobs.size(), errors, rateLimited);

        if (rateLimited && jobs.isEmpty()) {
            return FetchResult.rateLimited(elapsed(start));
        }
        if (jobs.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }
        return FetchResult.success(jobs, elapsed(start));
    }

    protected String resolveUrl(FetchContext context) {
        Object url = context.config().get("url");
        return url != null ? url.toString() : null;
    }

    protected int parseIntConfig(FetchContext context, String key, int defaultValue) {
        Object val = context.config().get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
