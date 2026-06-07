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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetch strategy that uses the LinkedIn MCP server (via HTTP) to search for jobs.
 * Extracts transport logic previously embedded in LinkedInJobSearchService.
 */
@Slf4j
@Component
public class McpStrategy implements FetchStrategy {

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
            return FetchResult.empty(elapsed);
        }
        return FetchResult.success(allJobs, elapsed);
    }

    /**
     * Parse the MCP search_jobs response into RawAggregatorJob records.
     * Response structure: { structuredContent: { sections: { search_results: "..." }, job_ids: [...] } }
     */
    List<RawAggregatorJob> parseSearchResponse(JsonNode result) {
        List<RawAggregatorJob> jobs = new ArrayList<>();

        JsonNode sc = result.path("structuredContent");
        JsonNode jobIds = sc.path("job_ids");
        String searchText = sc.path("sections").path("search_results").asText("");

        if (searchText.isBlank() || !jobIds.isArray()) {
            return jobs;
        }

        String[] lines = searchText.split("\n");
        List<ParsedLinkedInJob> parsed = parseJobLines(lines);

        int idIdx = 0;
        for (ParsedLinkedInJob pj : parsed) {
            if (idIdx >= jobIds.size()) break;

            String jobId = jobIds.get(idIdx).asText();
            String linkedinUrl = "https://www.linkedin.com/jobs/view/" + jobId + "/";
            idIdx++;

            jobs.add(new RawAggregatorJob(
                    jobId,
                    pj.title(),
                    pj.company(),
                    pj.location(),
                    null, // description fetched later by ingestion service
                    linkedinUrl,
                    null, // postedDate not in search results
                    null, null, null, // salary not in search results
                    null // rawJson
            ));
        }

        return jobs;
    }

    /**
     * Parse LinkedIn's text-based search results format: title, company, location(workType) lines.
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
                    parsedJobs.add(new ParsedLinkedInJob(title, company, line));
                }
            }
        }

        return parsedJobs;
    }

    private boolean isLocationLine(String line) {
        return line.contains("(Hybrid)") || line.contains("(Remote)")
                || line.contains("(On-site)") || line.contains("(On-Site)")
                || line.contains("(Onsite)");
    }

    record ParsedLinkedInJob(String title, String company, String location) {}
}
