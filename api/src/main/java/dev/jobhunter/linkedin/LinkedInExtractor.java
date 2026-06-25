package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInExtractor implements FetchStrategy {

    private static final Pattern RELATIVE_TIME_PATTERN =
            Pattern.compile("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago");
    private static final Pattern JOB_ID_FROM_URL_PATTERN =
            Pattern.compile("/jobs/view/(\\d+)");

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public LinkedInExtractor(HttpMcpClient httpMcpClient, LinkedInRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.LINKEDIN;
    }

    @Override
    public String name() {
        return "linkedin";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();

        if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
            log.warn("LinkedIn rate limit reached for SEARCH, skipping extraction for endpoint {}", endpoint.getId());
            return FetchResult.error("Rate limit reached", elapsed(start));
        }

        try {
            String searchTerm = endpoint.getAtsSlug() != null
                    ? endpoint.getAtsSlug()
                    : endpoint.getCompany().getName();

            Map<String, Object> params = Map.of(
                    "keywords", searchTerm,
                    "company", endpoint.getCompany().getName()
            );

            JsonNode response = httpMcpClient.callTool("search_jobs", params);
            List<RawAggregatorJob> jobs = parseJobResults(response);

            // Filter out jobs from other companies (LinkedIn search returns mixed results)
            String expectedCompany = endpoint.getCompany().getName().toLowerCase();
            jobs = jobs.stream()
                    .filter(job -> {
                        if (job.companyName() == null || job.companyName().isBlank()) return true; // keep if unknown
                        String actual = job.companyName().toLowerCase();
                        return actual.contains(expectedCompany) || expectedCompany.contains(actual);
                    })
                    .toList();

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("LinkedIn [{}]: extracted {} jobs", endpoint.getCompany().getName(), jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (McpClientException e) {
            log.error("LinkedIn extraction failed for {}: {}", endpoint.getCompany().getName(), e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        } catch (Exception e) {
            log.error("LinkedIn extraction unexpected error for {}: {}", endpoint.getCompany().getName(), e.getMessage());
            return FetchResult.error("Unexpected error: " + e.getMessage(), elapsed(start));
        }
    }

    private List<RawAggregatorJob> parseJobResults(JsonNode response) {
        // New MCP page-structured format: response has content[], structuredContent
        JsonNode structured = response.path("structuredContent");
        if (!structured.isMissingNode() && structured.has("job_ids")) {
            return parseStructuredResults(structured);
        }

        // Also try parsing content[0].text as JSON (same structure)
        JsonNode contentArray = response.path("content");
        if (contentArray.isArray() && !contentArray.isEmpty()) {
            JsonNode textNode = contentArray.get(0).path("text");
            if (textNode.isTextual()) {
                try {
                    JsonNode parsed = objectMapper.readTree(textNode.asText());
                    if (parsed.has("job_ids")) {
                        return parseStructuredResults(parsed);
                    }
                } catch (Exception ignored) {
                    // Not JSON or wrong format, fall through to legacy
                }
            }
        }

        // Legacy format: flat array of job objects
        List<RawAggregatorJob> jobs = new ArrayList<>();
        JsonNode jobsNode = response.path("jobs");
        if (!jobsNode.isArray()) {
            jobsNode = response.isArray() ? response : response.path("results");
        }
        if (!jobsNode.isArray()) {
            return jobs;
        }
        for (JsonNode jobNode : jobsNode) {
            RawAggregatorJob job = mapToRawAggregatorJob(jobNode);
            if (job != null) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private List<RawAggregatorJob> parseStructuredResults(JsonNode structured) {
        List<RawAggregatorJob> jobs = new ArrayList<>();

        JsonNode jobIds = structured.path("job_ids");
        JsonNode refs = structured.path("references").path("search_results");
        String sectionText = structured.path("sections").path("search_results").asText("");

        if (!jobIds.isArray() || jobIds.isEmpty()) {
            return jobs;
        }

        // Collect job references (kind="job") in order
        List<JsonNode> jobRefs = new ArrayList<>();
        if (refs.isArray()) {
            for (JsonNode ref : refs) {
                if ("job".equals(ref.path("kind").asText())) {
                    jobRefs.add(ref);
                }
            }
        }

        // Parse section text to extract per-job metadata blocks
        Map<String, JobTextBlock> textBlocks = parseJobTextBlocks(sectionText, jobRefs);

        // Build a set of job IDs for quick lookup
        Set<String> jobIdSet = new LinkedHashSet<>();
        for (JsonNode id : jobIds) {
            jobIdSet.add(id.asText());
        }

        for (JsonNode ref : jobRefs) {
            String title = ref.path("text").asText("");
            if (title.isBlank()) continue;

            String urlPath = ref.path("url").asText("");
            String externalId = extractJobIdFromUrl(urlPath);
            if (externalId == null) {
                // Fallback: use hash of URL
                externalId = String.valueOf(urlPath.hashCode());
            }

            String fullUrl = "https://www.linkedin.com" + urlPath;
            JobTextBlock block = textBlocks.get(title);
            String company = block != null ? block.company : null;
            String location = block != null ? block.location : null;
            LocalDate postedDate = block != null ? block.postedDate : null;
            if (postedDate == null) {
                postedDate = parseDateOrNull(ref, "listed_at", "posted_date", "date_posted");
            }

            ObjectNode rawNode = objectMapper.createObjectNode();
            rawNode.put("id", externalId);
            rawNode.put("title", title);
            rawNode.put("url", fullUrl);
            if (company != null) rawNode.put("company", company);
            if (location != null) rawNode.put("location", location);
            if (postedDate != null) rawNode.put("posted_date", postedDate.toString());

            jobs.add(new RawAggregatorJob(
                    externalId, title, company, location, null, fullUrl,
                    postedDate, null, null, null, rawNode.toString()
            ));
        }

        return jobs;
    }

    private String extractJobIdFromUrl(String urlPath) {
        Matcher m = JOB_ID_FROM_URL_PATTERN.matcher(urlPath);
        return m.find() ? m.group(1) : null;
    }

    private Map<String, JobTextBlock> parseJobTextBlocks(String sectionText, List<JsonNode> jobRefs) {
        Map<String, JobTextBlock> blocks = new LinkedHashMap<>();
        if (sectionText.isBlank() || jobRefs.isEmpty()) return blocks;

        // Get ordered titles
        List<String> titles = new ArrayList<>();
        for (JsonNode ref : jobRefs) {
            String t = ref.path("text").asText("");
            if (!t.isBlank()) titles.add(t);
        }

        String[] lines = sectionText.split("\n");

        for (int t = 0; t < titles.size(); t++) {
            String title = titles.get(t);
            // Find this title in the section text lines
            int titleIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals(title)) {
                    titleIdx = i;
                    break;
                }
            }
            if (titleIdx < 0) continue;

            // Find the end boundary (next title or end of lines)
            int endIdx = lines.length;
            for (int nt = t + 1; nt < titles.size(); nt++) {
                String nextTitle = titles.get(nt);
                for (int i = titleIdx + 1; i < lines.length; i++) {
                    if (lines[i].trim().equals(nextTitle)) {
                        endIdx = i;
                        break;
                    }
                }
                if (endIdx < lines.length) break;
            }

            // Extract metadata from lines between titleIdx+1 and endIdx
            String company = null;
            String location = null;
            LocalDate postedDate = null;

            // Line immediately after title is typically the company
            if (titleIdx + 1 < endIdx) {
                company = lines[titleIdx + 1].trim();
                if (company.isBlank() || isAnnotation(company)) company = null;
            }
            // Line after company is typically location
            if (titleIdx + 2 < endIdx) {
                String locCandidate = lines[titleIdx + 2].trim();
                if (!locCandidate.isBlank() && !isAnnotation(locCandidate)) {
                    location = locCandidate;
                }
            }
            // Scan block for relative time
            for (int i = titleIdx + 1; i < endIdx; i++) {
                LocalDate date = parseRelativeTimeString(lines[i].trim());
                if (date != null) {
                    postedDate = date;
                    break;
                }
            }

            blocks.put(title, new JobTextBlock(company, location, postedDate));
        }

        return blocks;
    }

    private boolean isAnnotation(String line) {
        String lower = line.toLowerCase();
        return lower.equals("promoted") || lower.equals("viewed") || lower.equals("apply")
                || lower.startsWith("actively reviewing")
                || lower.endsWith("alumni work here")
                || lower.startsWith("set alert")
                || lower.startsWith("jump to");
    }

    private LocalDate parseRelativeTimeString(String text) {
        Matcher m = RELATIVE_TIME_PATTERN.matcher(text);
        if (!m.find()) return null;

        int amount = Integer.parseInt(m.group(1));
        String unit = m.group(2);

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

    private record JobTextBlock(String company, String location, LocalDate postedDate) {}

    private RawAggregatorJob mapToRawAggregatorJob(JsonNode node) {
        String title = getTextOrNull(node, "title", "job_title");
        if (title == null || title.isBlank()) {
            return null;
        }

        String externalId = getTextOrNull(node, "id", "job_id");
        if (externalId == null) {
            String url = getTextOrNull(node, "url", "job_url", "link");
            externalId = url != null ? String.valueOf(url.hashCode()) : UUID.randomUUID().toString();
        }

        String location = getTextOrNull(node, "location");
        String description = getTextOrNull(node, "description", "summary");
        String applyUrl = getTextOrNull(node, "url", "job_url", "link", "apply_url");
        String rawJson = node.toString();

        BigDecimal salaryMin = getDecimalOrNull(node, "salary_min", "min_salary");
        BigDecimal salaryMax = getDecimalOrNull(node, "salary_max", "max_salary");
        String salaryCurrency = getTextOrNull(node, "salary_currency", "currency");
        LocalDate postedDate = parseDateOrNull(node, "posted_date", "date_posted", "listed_at");

        return new RawAggregatorJob(
                externalId, title, null, location, description, applyUrl,
                postedDate, salaryMin, salaryMax, salaryCurrency, rawJson
        );
    }

    private String getTextOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private BigDecimal getDecimalOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return BigDecimal.valueOf(value.asDouble());
            }
        }
        return null;
    }

    private LocalDate parseDateOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                String text = value.asText();
                // Try ISO date format
                try {
                    return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    // Try relative time ("2 weeks ago")
                    LocalDate relative = parseRelativeTimeString(text);
                    if (relative != null) return relative;
                }
            }
            // Handle epoch milliseconds (LinkedIn listed_at)
            if (value.isNumber()) {
                long epochMs = value.asLong();
                if (epochMs > 1_000_000_000_000L) { // clearly ms, not seconds
                    return Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                } else if (epochMs > 1_000_000_000L) { // epoch seconds
                    return Instant.ofEpochSecond(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                }
            }
        }
        return null;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
