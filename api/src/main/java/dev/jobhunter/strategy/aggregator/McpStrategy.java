package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.linkedin.HttpMcpClient;
import dev.jobhunter.linkedin.LinkedInRateLimiter;
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

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;

    public McpStrategy(HttpMcpClient httpMcpClient, LinkedInRateLimiter rateLimiter) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
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

        List<RawAggregatorJob> allJobs = new ArrayList<>();
        String lastError = null;
        int errorCount = 0;
        String datePosted = context.config() != null
                ? (String) context.config().getOrDefault("date-posted", "week")
                : "week";

        for (String keyword : keywords) {
            for (String location : locations) {
                if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
                    log.warn("Rate limit hit during LinkedIn MCP fetch");
                    if (allJobs.isEmpty()) {
                        return FetchResult.rateLimited(Duration.between(start, Instant.now()));
                    }
                    // Return what we have so far
                    return FetchResult.success(allJobs, Duration.between(start, Instant.now()));
                }

                try {
                    Map<String, Object> params = Map.of(
                            "keywords", keyword,
                            "location", location,
                            "date_posted", datePosted
                    );
                    JsonNode result = httpMcpClient.callTool("search_jobs", params);
                    List<RawAggregatorJob> jobs = parseSearchResponse(result);
                    allJobs.addAll(jobs);
                } catch (Exception e) {
                    log.error("LinkedIn MCP search failed for '{}' in '{}': {}", keyword, location, e.getMessage());
                    lastError = e.getMessage();
                    errorCount++;
                }

                if (allJobs.size() >= context.maxResults()) {
                    break;
                }
            }
            if (allJobs.size() >= context.maxResults()) {
                break;
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        if (allJobs.isEmpty()) {
            if (errorCount > 0) {
                return FetchResult.error("All searches failed (" + errorCount + "): " + lastError, elapsed);
            }
            return FetchResult.empty(elapsed);
        }
        return FetchResult.success(allJobs, elapsed);
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
            String title = ref.path("text").asText("").trim();
            String jobId = extractJobIdFromUrl(url);
            if (jobId != null && !title.isBlank()) {
                refs.add(new ReferenceJob(jobId, title));
            }
        }
        return refs;
    }

    /**
     * Match reference jobs to text-parsed entries by title to get company/location/date.
     * Uses consume-once matching to handle duplicate titles correctly.
     */
    List<RawAggregatorJob> alignReferencesWithText(List<ReferenceJob> refJobs, List<ParsedLinkedInJob> textParsed) {
        List<RawAggregatorJob> jobs = new ArrayList<>();
        boolean[] used = new boolean[textParsed.size()];

        for (ReferenceJob ref : refJobs) {
            String company = null;
            String location = null;
            LocalDate postedDate = null;

            // Find matching text entry by title (consume-once)
            for (int i = 0; i < textParsed.size(); i++) {
                if (used[i]) continue;
                if (titlesMatch(ref.title(), textParsed.get(i).title())) {
                    company = textParsed.get(i).company();
                    location = textParsed.get(i).location();
                    postedDate = textParsed.get(i).postedDate();
                    used[i] = true;
                    break;
                }
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

                    // Scan next 1-6 lines for relative time ("2 days ago", "1 week ago").
                    // LinkedIn inserts "Promoted", "Easy Apply", job-level tags between
                    // the location line and the date line, so 3 lines is often not enough.
                    LocalDate postedDate = null;
                    for (int j = i + 1; j <= Math.min(i + 6, lines.length - 1); j++) {
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
}
