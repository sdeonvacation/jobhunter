package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.resolution.AtsDetector;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves job context for a URL using, in order of cost:
 * 1. DB lookup by apply URL (no external calls)
 * 2. LinkedIn MCP get_job_details (budgeted + rate limited)
 * 3. ATS page scraping via OpenGraph meta tags (no LinkedIn calls)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class JobContextResolverImpl implements JobContextResolver {

    private static final Pattern LINKEDIN_JOB_ID = Pattern.compile("/(?:jobs/view/|jobPostingId=)(\\d+)");
    private static final Pattern ANY_JOB_ID = Pattern.compile("(?:gh_jid=|jobid=|/job/)(\\d+)");
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int FETCH_TIMEOUT_MS = 10_000;

    private final JobPostingRepository jobPostingRepository;
    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final AtsDetector atsDetector;
    private final ObjectMapper objectMapper;

    public JobContextResolverImpl(JobPostingRepository jobPostingRepository,
                                  HttpMcpClient httpMcpClient,
                                  LinkedInRateLimiter rateLimiter,
                                  AtsDetector atsDetector,
                                  ObjectMapper objectMapper) {
        this.jobPostingRepository = jobPostingRepository;
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.atsDetector = atsDetector;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JobContext> resolve(String jobUrl, CallBudget budget) {
        if (jobUrl == null || jobUrl.isBlank()) {
            return Optional.empty();
        }

        // 1. DB path — cheapest, no external calls
        Optional<JobContext> fromDb = resolveFromDb(jobUrl);
        if (fromDb.isPresent()) {
            return fromDb;
        }

        // 2. LinkedIn URL path
        if (isLinkedInUrl(jobUrl)) {
            return resolveFromLinkedIn(jobUrl, budget);
        }

        // 3. ATS URL fallback
        return resolveFromAts(jobUrl);
    }

    private Optional<JobContext> resolveFromDb(String jobUrl) {
        Optional<JobPosting> job = jobPostingRepository.findFirstByApplyUrl(jobUrl);
        if (job.isEmpty()) {
            job = jobPostingRepository.findFirstByApplyUrlStartingWith(jobUrl);
        }
        if (job.isEmpty()) {
            // URL formats vary (e.g. "?jobid=8545023002" vs "/job/8545023002?gh_jid=8545023002");
            // fall back to matching the numeric job id embedded in the apply URL.
            Matcher matcher = ANY_JOB_ID.matcher(jobUrl);
            if (matcher.find()) {
                job = jobPostingRepository.findFirstByApplyUrlContaining(matcher.group(1));
            }
        }
        return job.map(this::toJobContext);
    }

    private JobContext toJobContext(JobPosting job) {
        AtsType atsType = null;
        if (job.getApplyUrl() != null) {
            atsType = atsDetector.detectFromUrl(job.getApplyUrl())
                    .map(AtsDetector.DetectionResult::atsType)
                    .orElse(null);
        }
        String companyName = job.getCompany() != null ? job.getCompany().getName() : null;
        return new JobContext(
                job.getTitle(),
                companyName,
                job.getLocation(),
                job.getPostedDate(),
                job.getApplyUrl(),
                atsType,
                null,
                job.getId()
        );
    }

    private boolean isLinkedInUrl(String url) {
        return url.contains("linkedin.com/jobs") || url.contains("/jobs/view/");
    }

    private Optional<JobContext> resolveFromLinkedIn(String jobUrl, CallBudget budget) {
        Matcher matcher = LINKEDIN_JOB_ID.matcher(jobUrl);
        if (!matcher.find()) {
            log.debug("No numeric job id found in LinkedIn URL: {}", jobUrl);
            return Optional.empty();
        }
        String jobId = matcher.group(1);

        if (!budget.trySpend()) {
            log.warn("Call budget exhausted, skipping LinkedIn job details fetch for {}", jobUrl);
            return Optional.empty();
        }

        if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
            log.warn("Rate limit reached for PROFILE, cannot fetch LinkedIn job details for {}", jobUrl);
            return Optional.empty();
        }

        try {
            JsonNode response = httpMcpClient.callTool("get_job_details", Map.of("job_id", jobId));
            JobContext context = parseJobDetails(response, jobUrl, jobId);
            if (context == null) {
                log.warn("Incomplete job details from LinkedIn for job id {}", jobId);
                return Optional.empty();
            }
            return Optional.of(context);
        } catch (Exception e) {
            log.warn("Failed to fetch LinkedIn job details for {}: {}", jobUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses get_job_details response defensively. Handles structuredContent.sections,
     * content[0].text containing JSON with the same shape, and flat fields.
     */
    private JobContext parseJobDetails(JsonNode response, String jobUrl, String jobId) {
        if (response == null) {
            return null;
        }

        String title = extractField(response, "title");
        String company = extractField(response, "company");
        String location = extractField(response, "location");

        if (title == null || company == null) {
            return null;
        }

        return new JobContext(title, company, location, null, jobUrl, AtsType.LINKEDIN, jobId, null);
    }

    private String extractField(JsonNode response, String fieldName) {
        // structuredContent.sections.<field>
        JsonNode sections = response.path("structuredContent").path("sections");
        String value = textOrNull(sections.path(fieldName));
        if (value != null) {
            return value;
        }

        // content[0].text containing JSON with sections.<field> or flat <field>
        JsonNode contentArray = response.path("content");
        if (contentArray.isArray() && !contentArray.isEmpty()) {
            JsonNode textNode = contentArray.get(0).path("text");
            if (textNode.isTextual()) {
                try {
                    JsonNode parsed = objectMapper.readTree(textNode.asText());
                    value = textOrNull(parsed.path("sections").path(fieldName));
                    if (value == null) {
                        value = textOrNull(parsed.path(fieldName));
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse content[0].text as JSON for field '{}'", fieldName);
                }
            }
        }

        // Flat field on response
        if (value == null) {
            value = textOrNull(response.path(fieldName));
        }
        return value;
    }

    private String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private Optional<JobContext> resolveFromAts(String jobUrl) {
        AtsType atsType = atsDetector.detectFromUrl(jobUrl)
                .map(AtsDetector.DetectionResult::atsType)
                .orElse(null);

        try {
            Document doc = Jsoup.connect(jobUrl)
                    .timeout(FETCH_TIMEOUT_MS)
                    .userAgent(USER_AGENT)
                    .get();

            String title = metaContent(doc, "og:title");
            String company = metaContent(doc, "og:site_name");

            if (title == null || company == null) {
                log.warn("Missing og:title/og:site_name on ATS job page: {}", jobUrl);
                return Optional.empty();
            }

            title = stripCompanySuffix(title, company);

            return Optional.of(new JobContext(title, company, null, null, jobUrl, atsType, null, null));
        } catch (Exception e) {
            log.warn("Failed to fetch ATS job page {}: {}", jobUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private String metaContent(Document doc, String property) {
        var meta = doc.selectFirst("meta[property=" + property + "]");
        if (meta == null) {
            return null;
        }
        String content = meta.attr("content");
        return content == null || content.isBlank() ? null : content.trim();
    }

    /**
     * Cleans an og:title: strips a trailing " at <company>" suffix and a
     * " | <tagline>" suffix when the tagline contains the company name
     * (e.g. "Senior Software Engineer (m/f/d) | Lead Your Journey - with Flix.").
     */
    private String stripCompanySuffix(String title, String company) {
        if (title == null || company == null || company.isBlank()) {
            return title;
        }
        String marker = " at " + company;
        if (title.endsWith(marker)) {
            return title.substring(0, title.length() - marker.length()).trim();
        }
        int pipe = title.indexOf('|');
        if (pipe > 0) {
            String suffix = title.substring(pipe + 1);
            if (suffix.toLowerCase(Locale.ROOT).contains(company.toLowerCase(Locale.ROOT))) {
                return title.substring(0, pipe).trim();
            }
        }
        return title;
    }
}