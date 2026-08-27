package dev.jobhunter.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Enriches aggregator-sourced jobs (excluding LinkedIn) that have short/stub descriptions
 * by fetching the full job page from applyUrl and extracting text via Jsoup.
 */
@Slf4j
@Order(2)
@Component
@ConditionalOnProperty(prefix = "aggregator.enrichment", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AggregatorDescriptionEnricher implements PostIngestionEnricher {

    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    /** After this many consecutive short/empty enrichment attempts, deactivate the job
     *  as `url-dead-stuck` so it stops blocking the queue. Counter encoded in filter_reason. */
    private static final int STUCK_THRESHOLD = 3;
    /** tyomarkkinatori.fi public API requires a posting UUID in the path;
     *  reject paths that don't match before hitting the network. */
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final WebClient webClient;
    private final JobPostingRepository jobPostingRepository;
    private final MatchScoreRepository matchScoreRepository;
    private final DescriptionFilterChain descriptionFilterChain;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int batchSize;
    private final int delayBetweenMs;
    private final int minDescriptionLength;

    public AggregatorDescriptionEnricher(
            WebClient webClient,
            JobPostingRepository jobPostingRepository,
            MatchScoreRepository matchScoreRepository,
            DescriptionFilterChain descriptionFilterChain,
            @Value("${aggregator.enrichment.batch-size:5}") int batchSize,
            @Value("${aggregator.enrichment.delay-between-ms:2000}") int delayBetweenMs,
            @Value("${aggregator.enrichment.min-description-length:500}") int minDescriptionLength) {
        this.webClient = webClient;
        this.jobPostingRepository = jobPostingRepository;
        this.matchScoreRepository = matchScoreRepository;
        this.descriptionFilterChain = descriptionFilterChain;
        this.batchSize = batchSize;
        this.delayBetweenMs = delayBetweenMs;
        this.minDescriptionLength = minDescriptionLength;
    }

    @Override
    public void enrich(JobSource source, int created) {
        if (source == JobSource.LINKEDIN) return;
        if (!source.isAggregator() && source != JobSource.DIRECT) return;
        // Note: do not gate on `created == 0`. WIF crawls always return 0 new jobs
        // (everything is a duplicate or filtered), but the aggregator backlog (jobs
        // that already exist with null/short descriptions) still needs to be enriched.
        // The query in `enrichDescriptions` is source-agnostic, so it can drain the
        // backlog on any aggregator pass.
        enrichDescriptions();
    }

    void enrichDescriptions() {
        List<JobSource> sourcesToEnrich = new ArrayList<>(JobSource.aggregators());
        sourcesToEnrich.add(JobSource.DIRECT);

        List<JobPosting> jobs = jobPostingRepository
                .findAggregatorJobsNeedingDescription(sourcesToEnrich, minDescriptionLength);

        if (jobs.isEmpty()) {
            return;
        }

        List<JobPosting> batch = jobs.size() > batchSize
                ? jobs.subList(0, batchSize)
                : jobs;

        int enrichedCount = 0;

        for (JobPosting job : batch) {
            try {
                String extractedText = fetchDescription(job.getApplyUrl());
                if (extractedText == null || extractedText.isBlank()) {
                    log.debug("Empty response from applyUrl for job [{}]", job.getExternalId());
                    boolean wasPending = job.getVisaSponsorship() == VisaSponsorship.PENDING;
                    boolean deactivated = bumpStuckOrDeactivate(job, "empty-response");
                    if (!deactivated) {
                        deactivatePendingVisa(job, "visa: pending - no description available (empty response)");
                        if (!wasPending) {
                            // Non-pending: bumpStuckOrDeactivate set filter_reason but did not
                            // persist (to avoid double-save with deactivatePendingVisa which is a
                            // no-op here). Save now so the counter sticks across cycles.
                            jobPostingRepository.save(job);
                        }
                    }
                    continue;
                }
                if (extractedText.length() < minDescriptionLength || extractedText.length() <= currentDescriptionLength(job)) {
                    log.debug("No better text for job [{}] from {} (extractedLen={}, currentLen={}, minRequired={})",
                            job.getExternalId(), job.getApplyUrl(), extractedText.length(),
                            currentDescriptionLength(job), minDescriptionLength);
                    boolean wasPending = job.getVisaSponsorship() == VisaSponsorship.PENDING;
                    boolean deactivated = bumpStuckOrDeactivate(job, "short-text");
                    if (!deactivated) {
                        deactivatePendingVisa(job, "visa: pending - no description available (no better text)");
                        if (!wasPending) {
                            jobPostingRepository.save(job);
                        }
                    }
                    continue;
                }

                updateJobDescription(job, extractedText);
                enrichedCount++;

                if (delayBetweenMs > 0) {
                    Thread.sleep(delayBetweenMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Aggregator enrichment interrupted after {}/{} jobs", enrichedCount, batch.size());
                break;
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    job.setActive(false);
                    job.setFilterReason("url-dead-" + e.getStatusCode().value());
                    jobPostingRepository.save(job);
                    log.info("Deactivated dead-URL job [{}] (HTTP {}): {}", job.getExternalId(), e.getStatusCode().value(), job.getApplyUrl());
                } else {
                    log.warn("Failed to enrich aggregator job [{}] from {}: {}",
                            job.getExternalId(), job.getApplyUrl(), e.getMessage());
                    deactivatePendingVisa(job, "visa: pending - enrichment failed");
                }
            } catch (Exception e) {
                log.warn("Failed to enrich aggregator job [{}] from {}: {}",
                        job.getExternalId(), job.getApplyUrl(), e.getMessage());
                deactivatePendingVisa(job, "visa: pending - enrichment failed");
            }
        }

        log.info("Enriched aggregator job descriptions: {}/{}", enrichedCount, batch.size());
    }

    @Transactional
    void updateJobDescription(JobPosting job, String extractedText) {
        job.setDescription(extractedText);
        descriptionFilterChain.refilter(job);
        jobPostingRepository.save(job);
        // Delete existing score so job gets rescored with new description
        matchScoreRepository.deleteByJobId(job.getId());
    }

    /**
     * Deactivates a job that is still PENDING visa status when enrichment cannot complete
     * (empty response, no better text, or exception). No-op if visa status is not PENDING.
     */
    @Transactional
    void deactivatePendingVisa(JobPosting job, String reason) {
        if (job.getVisaSponsorship() != VisaSponsorship.PENDING) {
            return;
        }
        job.setVisaSponsorship(VisaSponsorship.UNKNOWN);
        job.setActive(false);
        job.setFilterReason(reason);
        jobPostingRepository.save(job);
        log.debug("Deactivated PENDING visa job [{}]: {}", job.getExternalId(), reason);
    }

    /**
     * Tracks repeated short/empty enrichment failures via `filter_reason` and deactivates
     * the job as `url-dead-stuck` after STUCK_THRESHOLD attempts. Prevents head-of-queue
     * jobs that consistently return <500 chars (e.g. tyomarkkinatori.fi shell pages)
     * from cycling forever and blocking newer jobs behind them.
     *
     * Returns true iff the helper already persisted the job (i.e. it was deactivated).
     * Otherwise the caller is responsible for persisting the bumped counter (e.g. via
     * deactivatePendingVisa or its own save).
     */
    @Transactional
    boolean bumpStuckOrDeactivate(JobPosting job, String failureKind) {
        String prev = job.getFilterReason();
        int attempts = 0;
        if (prev != null && prev.startsWith("enrich-stuck-")) {
            try { attempts = Integer.parseInt(prev.substring("enrich-stuck-".length())); } catch (NumberFormatException ignored) {}
        }
        attempts++;
        if (attempts >= STUCK_THRESHOLD) {
            job.setActive(false);
            job.setFilterReason("url-dead-stuck-" + attempts + "-" + failureKind);
            jobPostingRepository.save(job);
            log.info("Deactivated stuck job [{}] after {} attempts ({}): {}",
                    job.getExternalId(), attempts, failureKind, job.getApplyUrl());
            return true;
        }
        // Mutate in place; caller persists.
        job.setFilterReason("enrich-stuck-" + attempts);
        log.debug("Stuck attempt {}/{} for job [{}] ({})", attempts, STUCK_THRESHOLD,
                job.getExternalId(), failureKind);
        return false;
    }

    String fetchPage(String url) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(FETCH_TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw e;
            }
            log.warn("HTTP fetch failed for {}: {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("HTTP fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Parses HTML and extracts visible text content, stripping navigation and boilerplate.
     */
    String extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, footer, header, noscript, iframe").remove();

        String text = doc.body() != null ? doc.body().text() : "";
        if (text.isBlank()) {
            return null;
        }

        if (text.length() > MAX_DESCRIPTION_LENGTH) {
            text = text.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        return text;
    }

    String fetchDescription(String applyUrl) {
        if (applyUrl == null || applyUrl.isBlank()) return null;
        String host = null;
        try { host = new java.net.URI(applyUrl).getHost(); } catch (Exception e) { return defaultFetch(applyUrl); }
        if (host == null) return defaultFetch(applyUrl);
        try {
            if (host.contains("thehub.fi") || host.contains("thehub.io")) return fetchTheHubDescription(applyUrl);
            if (host.contains("jobly.fi")) return fetchJoblyDescription(applyUrl);
            if (host.contains("tyomarkkinatori.fi")) return fetchTyomarkkinatoriDescription(applyUrl);
        } catch (Exception e) {
            log.warn("Host-specific fetch failed for {} ({}), falling back to default", applyUrl, e.getMessage());
        }
        return defaultFetch(applyUrl);
    }

    /**
     * Walks every <script type="application/ld+json"> element and, if a JobPosting
     * (or JobListing) is found with a non-blank description, returns that description
     * as plain text. Returns null on no match or all-malformed JSON-LD blocks.
     */
    String tryExtractJsonLdDescription(String html) {
        if (html == null || html.isBlank()) return null;
        try {
            Document doc = Jsoup.parse(html);
            for (org.jsoup.nodes.Element script : doc.select("script[type=application/ld+json]")) {
                try {
                    JsonNode node = objectMapper.readTree(script.data());
                    JsonNode jp = findJobPosting(node);
                    if (jp != null) {
                        String desc = jp.path("description").asText(null);
                        if (desc != null && !desc.isBlank()) return htmlToText(desc);
                    }
                } catch (Exception ignored) {
                    // Malformed JSON-LD block; try the next one.
                }
            }
        } catch (Exception e) {
            log.debug("JSON-LD extraction failed: {}", e.getMessage());
        }
        return null;
    }

    private String defaultFetch(String url) {
        String html = fetchPage(url);
        if (html == null || html.isBlank()) return null;
        // Tier 1: JSON-LD JobPosting.description (works for many ATSes that SSR a shell
        // with an embedded JobPosting JSON-LD block but no usable body text).
        String ldText = tryExtractJsonLdDescription(html);
        if (ldText != null && !ldText.isBlank() && ldText.length() >= minDescriptionLength) {
            return ldText;
        }
        // Tier 2: plain body text.
        String bodyText = extractText(html);
        if (bodyText != null && bodyText.length() >= minDescriptionLength) {
            return bodyText;
        }
        // Tier 3: r.jina.ai reader proxy (renders SPA server-side). Cap to ONE attempt
        // per job; the caller's length check still gates whether the result is good enough.
        String readerText = fetchReaderProxy(url);
        if (readerText != null && !readerText.isBlank() && readerText.length() >= minDescriptionLength) {
            return readerText;
        }
        // Return the best we got (might still be short — let the caller's length check decide).
        if (readerText != null && !readerText.isBlank()) return readerText;
        if (bodyText != null && !bodyText.isBlank()) return bodyText;
        return ldText; // null or short — caller decides
    }

    private String fetchTheHubDescription(String applyUrl) throws Exception {
        String id = lastPathSegment(applyUrl);
        String apiUrl = "https://api.thehub.io/jobs/" + id;
        String body = webClient.get()
                .uri(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .retrieve().bodyToMono(String.class).timeout(FETCH_TIMEOUT).block();
        if (body == null || body.isBlank()) return null;
        JsonNode root = objectMapper.readTree(body);
        String html = root.path("doc").path("description").asText(null);
        return (html == null || html.isBlank()) ? null : htmlToText(html);
    }

    private String fetchJoblyDescription(String applyUrl) throws Exception {
        // jobly.fi bot-blocks generic fetches with 403 (Cloudflare/Akamai).
        // Try r.jina.ai reader proxy first — it renders the SPA server-side and
        // returns clean markdown/text for the page, including the JD. This is the
        // only reliable path for jobly.fi right now.
        String readerText = fetchReaderProxy(applyUrl);
        if (readerText != null && !readerText.isBlank()
                && readerText.length() >= minDescriptionLength) {
            return readerText;
        }
        // Fallback to direct fetch + JSON-LD parse (works when bot-block is off).
        String html = fetchPage(applyUrl);
        if (html == null || html.isBlank()) {
            return (readerText != null && !readerText.isBlank()) ? readerText : null;
        }
        Document doc = Jsoup.parse(html);
        for (org.jsoup.nodes.Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                JsonNode jp = findJobPosting(node);
                if (jp != null) {
                    String desc = jp.path("description").asText(null);
                    if (desc != null && !desc.isBlank()) return htmlToText(desc);
                }
            } catch (Exception ignored) {}
        }
        return extractText(html);
    }

    /**
     * Tier-2 fetch for tyomarkkinatori.fi: hit the unauthenticated public job-posting
     * API directly with a browser-like UA + Referer/Origin. The site's HTML pages are
     * JS-rendered shells (no SSR, no JSON-LD), so the default fetch + Jsoup path
     * returns a near-empty document. The public API also returns 404 for removed
     * postings, so we treat that as "empty" (caller bumps the stuck counter) rather
     * than as a transient error.
     */
    private String fetchTyomarkkinatoriDescription(String applyUrl) throws Exception {
        String lastSegment = lastPathSegment(applyUrl);
        if (lastSegment == null || !UUID_PATTERN.matcher(lastSegment).matches()) {
            log.debug("tyomarkkinatori applyUrl has no UUID in last segment: {}", applyUrl);
            return null;
        }
        String apiUrl = "https://tyomarkkinatori.fi/api/jobposting-new/v1/public/jobpostings/" + lastSegment;
        String body;
        try {
            body = webClient.get()
                    .uri(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .header("Referer", applyUrl)
                    .header("Origin", "https://tyomarkkinatori.fi")
                    .retrieve().bodyToMono(String.class).timeout(FETCH_TIMEOUT).block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.debug("tyomarkkinatori public API returned 404 for {} — posting removed", applyUrl);
                return null;
            }
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("tyomarkkinatori public API returned HTTP {} for {}: {}",
                        e.getStatusCode().value(), applyUrl, e.getMessage());
                return null;
            }
            throw e;
        }
        if (body == null || body.isBlank()) return null;
        JsonNode root = objectMapper.readTree(body);
        String text = root.path("position").path("jobDescription").path("en").asText(null);
        if (text == null || text.isBlank()) text = root.path("position").path("marketingDescription").path("en").asText(null);
        if (text == null || text.isBlank()) {
            log.debug("tyomarkkinatori public API returned empty body for {}", applyUrl);
            return null;
        }
        return text;
    }

    private String htmlToText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript").remove();
        String text = doc.body() != null ? doc.body().text() : html;
        if (text.length() > MAX_DESCRIPTION_LENGTH) text = text.substring(0, MAX_DESCRIPTION_LENGTH);
        return text.isBlank() ? null : text;
    }

    /**
     * Fetches a URL via the r.jina.ai reader proxy, which renders JS-rendered pages
     * server-side and returns clean markdown/text. Used as a fallback for sites that
     * bot-block generic fetchers (e.g. jobly.fi → 403).
     * Returns null on any failure (timeout, non-2xx, blank body, or Cloudflare
     * challenge page detected by signature phrases).
     */
    private String fetchReaderProxy(String applyUrl) {
        try {
            // Use the multi-arg URI constructor to preserve the `//` in the path.
            // WebClient.uri(String) routes through UriComponentsBuilder which collapses
            // the leading `//` in the path to `/:` (it treats the inner scheme as an
            // authority separator). Passing a pre-built URI keeps the raw path intact.
            URI applyUri = URI.create(applyUrl);
            URI proxyUri = new URI("https", null, "r.jina.ai", -1, "/" + applyUri.toString(), null, null);
            log.debug("r.jina.ai reader proxy attempt for {}", applyUrl);
            String body = webClient.get()
                    .uri(proxyUri)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Accept", "text/plain")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(FETCH_TIMEOUT)
                    .block();
            if (body == null || body.isBlank()) return null;
            if (isChallengePage(body)) {
                log.debug("r.jina.ai returned challenge page for {}", applyUrl);
                return null;
            }
            if (body.length() > MAX_DESCRIPTION_LENGTH) body = body.substring(0, MAX_DESCRIPTION_LENGTH);
            return body;
        } catch (Exception e) {
            log.debug("r.jina.ai reader proxy failed for {}: {}", applyUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Detects anti-bot challenge pages (Cloudflare "Just a moment..." / captcha).
     * Such pages can pass the 500-char minDescriptionLength gate and pollute scoring.
     */
    private boolean isChallengePage(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("just a moment")
                || lower.contains("performing security verification")
                || lower.contains("verify you are human")
                || lower.contains("checking your browser")
                || lower.contains("attention required! | cloudflare");
    }

    private String lastPathSegment(String url) {
        String path = url;
        int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#'); if (h >= 0) path = path.substring(0, h);
        int s = path.lastIndexOf('/');
        return (s >= 0 && s < path.length() - 1) ? path.substring(s + 1) : path;
    }

    private JsonNode findJobPosting(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isObject()) {
            JsonNode type = node.path("@type");
            if (type.isTextual() && ("JobPosting".equals(type.asText()) || "JobListing".equals(type.asText()))) return node;
            java.util.Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) { JsonNode f = findJobPosting(it.next()); if (f != null) return f; }
        } else if (node.isArray()) {
            for (JsonNode child : node) { JsonNode f = findJobPosting(child); if (f != null) return f; }
        }
        return null;
    }

    private int currentDescriptionLength(JobPosting job) {
        return job.getDescription() != null ? job.getDescription().length() : 0;
    }
}
