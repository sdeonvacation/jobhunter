package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.linkedin.HttpMcpClient;
import dev.jobhunter.linkedin.LinkedInRateLimiter;
import dev.jobhunter.linkedin.LinkedInSearchCursor;
import dev.jobhunter.linkedin.McpClientException;
import dev.jobhunter.linkedin.ToolCategory;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch strategy that uses the LinkedIn MCP server (via HTTP) to search for jobs.
 * Extracts transport logic previously embedded in LinkedInJobSearchService.
 */
@Slf4j
@Component
public class McpStrategy implements FetchStrategy {

    private static final Pattern RELATIVE_TIME =
            Pattern.compile("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERIFICATION_SUFFIX =
            Pattern.compile("\\s+with verification\\s*$", Pattern.CASE_INSENSITIVE);

    /** A 0-job response faster than this means the sidecar is not ready yet. */
    static final long INSTANT_EMPTY_THRESHOLD_MS = 1500;

    static final int DEFAULT_MAX_PAGES = 2;
    static final int DEFAULT_PAIRS_PER_RUN = 12;
    static final int DEFAULT_SEARCH_RESERVE = 5;

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final LinkedInSearchCursor searchCursor;

    public McpStrategy(HttpMcpClient httpMcpClient, LinkedInRateLimiter rateLimiter,
                       LinkedInSearchCursor searchCursor) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.searchCursor = searchCursor;
    }

    @Override
    public String name() {
        return "linkedin-mcp";
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.LINKEDIN;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        List<String> keywords = context.keywords();
        List<String> locations = context.locations();

        if (keywords == null || keywords.isEmpty() || locations == null || locations.isEmpty()) {
            return FetchResult.empty(Duration.between(start, Instant.now()));
        }

        Map<String, Object> config = context.config();
        String datePosted = configString(config, "date-posted", "past_24_hours");
        int maxPages = configInt(config, "max-pages", DEFAULT_MAX_PAGES);
        int pairsPerRun = configInt(config, "pairs-per-run", DEFAULT_PAIRS_PER_RUN);
        int searchReserve = configInt(config, "search-reserve", DEFAULT_SEARCH_RESERVE);

        List<SearchPair> pairs = buildRoundRobinPairs(keywords, locations);
        int totalPairs = pairs.size();
        int startOffset = Math.floorMod(searchCursor.getOffset(), totalPairs);
        int windowSize = Math.min(pairsPerRun, totalPairs);

        log.info("LinkedIn MCP search window: start={}, pairs-per-run={}, totalPairs={}",
                startOffset, windowSize, totalPairs);

        List<RawAggregatorJob> allJobs = new ArrayList<>();
        String lastError = null;
        int errorCount = 0;
        int attempted = 0;
        int cursorAdvance = 0;
        boolean rateLimited = false;

        for (int i = 0; i < windowSize; i++) {
            // The first pair is always attempted so that a tiny maxResults cannot skip the window.
            if (attempted > 0 && allJobs.size() >= context.maxResults()) {
                break;
            }

            int remaining = rateLimiter.getRemainingTokens(ToolCategory.SEARCH);
            if (remaining <= searchReserve) {
                log.info("LinkedIn MCP search stopped: SEARCH tokens remaining {} <= reserve {} (pairs attempted {})",
                        remaining, searchReserve, attempted);
                break;
            }

            SearchPair pair = pairs.get(Math.floorMod(startOffset + i, totalPairs));

            if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
                log.warn("Rate limit hit during LinkedIn MCP fetch");
                rateLimited = true;
                break;
            }
            attempted++;

            PairOutcome outcome = executePair(pair, datePosted, maxPages);
            if (outcome.instantEmpty()) {
                log.warn("LinkedIn MCP returned 0 jobs in {}ms for '{}' in '{}' - sidecar not ready, aborting run",
                        outcome.elapsedMs(), pair.keyword(), pair.location());
                break;
            }
            if (outcome.jobs() != null) {
                allJobs.addAll(outcome.jobs());
            } else {
                log.error("LinkedIn MCP search failed for '{}' in '{}': {}",
                        pair.keyword(), pair.location(), outcome.error());
                lastError = outcome.error();
                errorCount++;
            }
            // A definitive outcome (jobs returned or a permanently failed pair) moves the rotation on.
            cursorAdvance++;
        }

        searchCursor.advance(cursorAdvance, totalPairs);

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("LinkedIn MCP search run finished: pairs attempted={}, jobs collected={}, SEARCH tokens remaining={}",
                attempted, allJobs.size(), rateLimiter.getRemainingTokens(ToolCategory.SEARCH));

        if (allJobs.isEmpty()) {
            if (errorCount > 0) {
                return FetchResult.error("All searches failed (" + errorCount + "): " + lastError, elapsed);
            }
            if (rateLimited) {
                return FetchResult.rateLimited(elapsed);
            }
            return FetchResult.empty(elapsed);
        }
        return FetchResult.success(allJobs, elapsed);
    }

    /**
     * Build the keyword x location pairs so the keyword index varies fastest. Any window of
     * {@code keywords.size()} consecutive pairs therefore touches every keyword.
     */
    static List<SearchPair> buildRoundRobinPairs(List<String> keywords, List<String> locations) {
        int total = keywords.size() * locations.size();
        List<SearchPair> pairs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            pairs.add(new SearchPair(keywords.get(i % keywords.size()), locations.get(i / keywords.size())));
        }
        return pairs;
    }

    /**
     * Run one pair, refreshing the MCP session and retrying exactly once on failure.
     */
    private PairOutcome executePair(SearchPair pair, String datePosted, int maxPages) {
        try {
            return callSearch(pair, datePosted, maxPages);
        } catch (Exception e) {
            log.warn("LinkedIn MCP search failed for '{}' in '{}': {} - refreshing session and retrying once",
                    pair.keyword(), pair.location(), e.getMessage());
            try {
                httpMcpClient.isSessionValid();
                return callSearch(pair, datePosted, maxPages);
            } catch (Exception retryError) {
                log.error("LinkedIn MCP search retry failed for '{}' in '{}': {}",
                        pair.keyword(), pair.location(), retryError.getMessage());
                return PairOutcome.failure(retryError.getMessage());
            }
        }
    }

    private PairOutcome callSearch(SearchPair pair, String datePosted, int maxPages) {
        long callStart = System.currentTimeMillis();
        Map<String, Object> params = Map.of(
                "keywords", pair.keyword(),
                "location", pair.location(),
                "date_posted", datePosted,
                "max_pages", maxPages
        );
        JsonNode result = httpMcpClient.callTool("search_jobs", params);
        long elapsedMs = System.currentTimeMillis() - callStart;
        List<RawAggregatorJob> jobs = parseSearchResponse(result);
        if (jobs.isEmpty() && elapsedMs < INSTANT_EMPTY_THRESHOLD_MS) {
            return PairOutcome.instantEmpty(elapsedMs);
        }
        return PairOutcome.success(jobs);
    }

    private static int configInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static String configString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value instanceof String s && !s.isBlank() ? s : defaultValue;
    }

    /**
     * Parse the MCP search_jobs response into RawAggregatorJob records.
     * Uses references.search_results for authoritative (jobId, title) mapping,
     * then matches against text-parsed entries by title to get company/location.
     */
    List<RawAggregatorJob> parseSearchResponse(JsonNode result) {
        List<RawAggregatorJob> jobs = new ArrayList<>();

        // Try structuredContent (old format) or root-level fields
        JsonNode sc = result.has("structuredContent") ? result.path("structuredContent") : result;

        // Check for section_errors — MCP wraps Playwright/scrape failures as isError:false with section_errors
        JsonNode sectionErrors = sc.path("section_errors");
        if (!sectionErrors.isMissingNode() && sectionErrors.isObject() && sectionErrors.size() > 0) {
            JsonNode srError = sectionErrors.path("search_results");
            String errorType = srError.path("error_type").asText("");
            String errorMessage = srError.path("error_message").asText("");
            if (!errorType.isBlank()) {
                throw new McpClientException("LinkedIn scrape error [" + errorType + "]: " + errorMessage, 0);
            }
        }

        String searchText = sc.path("sections").path("search_results").asText("");
        JsonNode references = sc.path("references").path("search_results");

        // Primary path: use references for reliable ID-title alignment
        if (references.isArray() && references.size() > 0) {
            List<ParsedLinkedInJob> textParsed = searchText.isBlank()
                    ? List.of()
                    : parseJobLines(searchText.split("\n"));

            List<ReferenceJob> refJobs = parseReferences(references);
            jobs = alignReferencesWithText(refJobs, textParsed);

            if (!jobs.isEmpty()) {
                return jobs;
            }
        }

        // Fallback: old positional alignment (job_ids array)
        JsonNode jobIds = sc.path("job_ids");
        if (searchText.isBlank() || !jobIds.isArray()) {
            return jobs;
        }

        String[] lines = searchText.split("\n");
        List<ParsedLinkedInJob> parsed = parseJobLines(lines);

        // Only use positional fallback if counts match exactly
        if (parsed.size() != jobIds.size()) {
            log.warn("LinkedIn parser: text entries ({}) != job_ids ({}), skipping unreliable batch",
                    parsed.size(), jobIds.size());
            return jobs;
        }

        for (int i = 0; i < parsed.size(); i++) {
            ParsedLinkedInJob pj = parsed.get(i);
            String jobId = jobIds.get(i).asText();
            String linkedinUrl = "https://www.linkedin.com/jobs/view/" + jobId + "/";
            jobs.add(new RawAggregatorJob(
                    jobId, pj.title(), pj.company(), pj.location(),
                    null, linkedinUrl, pj.postedDate(), null, null, null, null
            ));
        }

        return jobs;
    }

    /**
     * Extract (jobId, title) pairs from references.search_results where kind=job.
     */
    List<ReferenceJob> parseReferences(JsonNode references) {
        List<ReferenceJob> refs = new ArrayList<>();
        for (JsonNode ref : references) {
            if (!"job".equals(ref.path("kind").asText(""))) continue;
            String url = ref.path("url").asText("");
            String title = normalizeReferenceTitle(ref.path("text").asText(""));
            String jobId = extractJobIdFromUrl(url);
            if (jobId != null && !title.isBlank()) {
                refs.add(new ReferenceJob(jobId, title));
            }
        }
        return refs;
    }

    private String normalizeReferenceTitle(String title) {
        return VERIFICATION_SUFFIX.matcher(title.trim()).replaceFirst("").trim();
    }

    /**
     * Match reference jobs to text-parsed entries by title to get company/location/date.
     * Only enriches a reference when its title identifies exactly one reference
     * and one text entry. Duplicate titles are ambiguous and must not inherit
     * metadata from an arbitrary result.
     */
    List<RawAggregatorJob> alignReferencesWithText(List<ReferenceJob> refJobs, List<ParsedLinkedInJob> textParsed) {
        List<RawAggregatorJob> jobs = new ArrayList<>();

        for (ReferenceJob ref : refJobs) {
            String company = null;
            String location = null;
            LocalDate postedDate = null;

            int matchingTextIndex = -1;
            int matchingTextCount = 0;
            for (int i = 0; i < textParsed.size(); i++) {
                if (titlesMatch(ref.title(), textParsed.get(i).title())) {
                    matchingTextIndex = i;
                    matchingTextCount++;
                }
            }

            // A title-only match is safe only when no other reference can
            // resolve to the same text entry.
            int matchingReferenceCount = 0;
            if (matchingTextCount == 1) {
                ParsedLinkedInJob candidate = textParsed.get(matchingTextIndex);
                for (ReferenceJob otherRef : refJobs) {
                    if (titlesMatch(otherRef.title(), candidate.title())) {
                        matchingReferenceCount++;
                    }
                }
            }

            if (matchingTextCount == 1 && matchingReferenceCount == 1) {
                ParsedLinkedInJob matched = textParsed.get(matchingTextIndex);
                company = matched.company();
                location = matched.location();
                postedDate = matched.postedDate();
            }

            String linkedinUrl = "https://www.linkedin.com/jobs/view/" + ref.jobId() + "/";
            jobs.add(new RawAggregatorJob(
                    ref.jobId(), ref.title(), company, location,
                    null, linkedinUrl, postedDate, null, null, null, null
            ));
        }

        return jobs;
    }

    private boolean titlesMatch(String refTitle, String textTitle) {
        if (refTitle.equalsIgnoreCase(textTitle)) return true;
        // References may truncate long titles; check prefix match
        if (refTitle.length() > 10 && textTitle.toLowerCase().startsWith(refTitle.toLowerCase())) return true;
        if (textTitle.length() > 10 && refTitle.toLowerCase().startsWith(textTitle.toLowerCase())) return true;
        return false;
    }

    private String extractJobIdFromUrl(String url) {
        // Pattern: /jobs/view/12345/ or full URL
        int viewIdx = url.indexOf("/jobs/view/");
        if (viewIdx < 0) return null;
        String after = url.substring(viewIdx + "/jobs/view/".length());
        int slashIdx = after.indexOf('/');
        return slashIdx > 0 ? after.substring(0, slashIdx) : after;
    }

    /**
     * Parse LinkedIn's text-based search results format: title, company, location(workType) lines.
     * Also scans the 1-3 lines following the location line for a relative time string ("X days ago").
     */
    List<ParsedLinkedInJob> parseJobLines(String[] lines) {
        List<ParsedLinkedInJob> parsedJobs = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isLocationLine(line) && i >= 2) {
                String company = lines[i - 1].trim();
                int titleIdx = i - 2;
                while (titleIdx >= 0 && lines[titleIdx].trim().endsWith("with verification")) {
                    titleIdx--;
                }
                String title = titleIdx >= 0 ? lines[titleIdx].trim() : "";

                if (!company.isEmpty() && !title.isEmpty()
                        && !company.contains("results") && !company.startsWith("Set alert")
                        && !company.startsWith("Jump to") && !company.endsWith("with verification")) {

                    // Scan next 1-10 lines for relative time ("2 days ago", "1 week ago").
                    // LinkedIn inserts "Promoted", "Easy Apply", "Reposted", and other badge
                    // lines between the location line and the date line; reposts can push the
                    // date to i+7 or beyond, so the window must be wider than 6.
                    LocalDate postedDate = null;
                    for (int j = i + 1; j <= Math.min(i + 10, lines.length - 1); j++) {
                        postedDate = parseRelativeTime(lines[j].trim());
                        if (postedDate != null) break;
                    }

                    parsedJobs.add(new ParsedLinkedInJob(title, company, line, postedDate));
                }
            }
        }

        return parsedJobs;
    }

    private LocalDate parseRelativeTime(String text) {
        Matcher m = RELATIVE_TIME.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group(1));
        String unit = m.group(2).toLowerCase();
        LocalDate today = LocalDate.now();
        return switch (unit) {
            case "second", "minute", "hour" -> today;
            case "day" -> today.minusDays(amount);
            case "week" -> today.minusWeeks(amount);
            case "month" -> today.minusMonths(amount);
            case "year" -> today.minusYears(amount);
            default -> null;
        };
    }

    private boolean isLocationLine(String line) {
        return line.contains("(Hybrid)") || line.contains("(Remote)")
                || line.contains("(On-site)") || line.contains("(On-Site)")
                || line.contains("(Onsite)");
    }

    record ParsedLinkedInJob(String title, String company, String location, LocalDate postedDate) {}
    record ReferenceJob(String jobId, String title) {}
    record SearchPair(String keyword, String location) {}

    record PairOutcome(List<RawAggregatorJob> jobs, String error, boolean instantEmpty, long elapsedMs) {
        static PairOutcome success(List<RawAggregatorJob> jobs) {
            return new PairOutcome(jobs, null, false, 0);
        }

        static PairOutcome failure(String error) {
            return new PairOutcome(null, error, false, 0);
        }

        static PairOutcome instantEmpty(long elapsedMs) {
            return new PairOutcome(null, null, true, elapsedMs);
        }
    }
}
