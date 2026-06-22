package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.ingestion.PostIngestionEnricher;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enriches LinkedIn job postings that were ingested without a description
 * by fetching details from the LinkedIn MCP server.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInDescriptionEnricher implements PostIngestionEnricher {

    private final HttpMcpClient httpMcpClient;
    private final Optional<LinkedInRateLimiter> rateLimiter;
    private final LinkedInMcpProperties mcpProperties;
    private final JobPostingRepository jobPostingRepository;
    private final LanguageFilter languageFilter;
    private final YoeFilter yoeFilter;

    public LinkedInDescriptionEnricher(HttpMcpClient httpMcpClient,
                                       Optional<LinkedInRateLimiter> rateLimiter,
                                       LinkedInMcpProperties mcpProperties,
                                       JobPostingRepository jobPostingRepository,
                                       LanguageFilter languageFilter,
                                       YoeFilter yoeFilter) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.mcpProperties = mcpProperties;
        this.jobPostingRepository = jobPostingRepository;
        this.languageFilter = languageFilter;
        this.yoeFilter = yoeFilter;
    }

    @Override
    public void enrich(JobSource source, int created) {
        if (source != JobSource.LINKEDIN || created <= 0) {
            return;
        }
        enrichDescriptions();
    }

    /**
     * Fetches job descriptions from LinkedIn MCP for jobs that were ingested without one.
     * Gated by enrichment.enabled config.
     */
    void enrichDescriptions() {
        LinkedInMcpProperties.EnrichmentConfig enrichment = mcpProperties.enrichment();
        if (!enrichment.enabled()) {
            return;
        }

        List<JobPosting> jobsWithoutDescription = jobPostingRepository
                .findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP);

        if (jobsWithoutDescription.isEmpty()) {
            return;
        }

        int batchSize = enrichment.batchSize();
        int delayBetweenMs = enrichment.delayBetweenMs();
        List<JobPosting> batch = jobsWithoutDescription.size() > batchSize
                ? jobsWithoutDescription.subList(0, batchSize)
                : jobsWithoutDescription;

        int enrichedCount = 0;
        int yoeFiltered = 0;

        for (JobPosting job : batch) {
            try {
                // Respect rate limits if limiter is available
                if (rateLimiter.isPresent() && !rateLimiter.get().acquire(ToolCategory.PROFILE)) {
                    log.warn("Rate limit reached for PROFILE, stopping LinkedIn description enrichment at {}/{}", enrichedCount, batch.size());
                    break;
                }

                JsonNode response = httpMcpClient.callTool("get_job_details", Map.of("job_id", job.getExternalId()));
                String description = extractDescription(response);

                if (description != null && !description.isBlank()) {
                    job.setDescription(description);

                    // Re-apply language filter now that description is available
                    FilterResult langResult = languageFilter.filter(job.getTitle(), description);
                    if (langResult.decision() == FilterDecision.SKIP) {
                        job.setLanguageFilter(FilterDecision.SKIP);
                        job.setFilterReason(langResult.reason());
                        log.debug("LinkedIn job [{}] filtered by language after enrichment: {}",
                                job.getExternalId(), langResult.reason());
                    } else {
                        Integer yoe = yoeFilter.extractYoe(description);
                        job.setRequiredYoe(yoe);
                        FilterResult yoeResult = yoeFilter.filter(yoe);
                        if (yoeResult.decision() == FilterDecision.SKIP) {
                            job.setLanguageFilter(FilterDecision.SKIP);
                            job.setFilterReason(yoeResult.reason());
                            yoeFiltered++;
                            log.debug("LinkedIn job [{}] YOE filter SKIP: yoe={} reason={}",
                                    job.getExternalId(), yoe, yoeResult.reason());
                        }
                    }

                    jobPostingRepository.save(job);
                    enrichedCount++;
                }

                // Delay between calls to avoid hammering the MCP server
                if (delayBetweenMs > 0) {
                    Thread.sleep(delayBetweenMs);
                }
            } catch (McpClientException e) {
                log.warn("Failed to fetch description for LinkedIn job [{}]: {}", job.getExternalId(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("LinkedIn enrichment interrupted after {}/{} jobs", enrichedCount, batch.size());
                break;
            } catch (Exception e) {
                log.warn("Unexpected error enriching LinkedIn job [{}]: {}", job.getExternalId(), e.getMessage());
            }
        }

        log.info("Enriched LinkedIn job descriptions: {}/{} (yoe-filtered: {})", enrichedCount, batch.size(), yoeFiltered);
    }

    /**
     * Extracts description text from MCP get_job_details response.
     * Handles both structuredContent format and content array format.
     */
    String extractDescription(JsonNode response) {
        if (response == null) {
            return null;
        }

        // Try structuredContent.sections.description or job_posting
        JsonNode sections = response.path("structuredContent").path("sections");
        JsonNode structured = sections.path("description");
        if (structured.isTextual() && !structured.asText().isBlank()) {
            return structured.asText();
        }
        structured = sections.path("job_posting");
        if (structured.isTextual() && !structured.asText().isBlank()) {
            return structured.asText();
        }

        // Try content array format: content[0].text contains JSON with sections.description
        JsonNode contentArray = response.path("content");
        if (contentArray.isArray() && !contentArray.isEmpty()) {
            JsonNode firstContent = contentArray.get(0);
            JsonNode textNode = firstContent.path("text");
            if (textNode.isTextual()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode parsed = mapper.readTree(textNode.asText());
                    // Try sections.description (legacy format)
                    JsonNode desc = parsed.path("sections").path("description");
                    if (desc.isTextual() && !desc.asText().isBlank()) {
                        return desc.asText();
                    }
                    // Try sections.job_posting (linkedin-scraper-mcp format)
                    desc = parsed.path("sections").path("job_posting");
                    if (desc.isTextual() && !desc.asText().isBlank()) {
                        return desc.asText();
                    }
                    // Fallback: description at top level of parsed content
                    desc = parsed.path("description");
                    if (desc.isTextual() && !desc.asText().isBlank()) {
                        return desc.asText();
                    }
                } catch (Exception e) {
                    // If text is not JSON, use it directly as description
                    String text = textNode.asText();
                    if (!text.isBlank() && text.length() > 50) {
                        return text;
                    }
                }
            }
        }

        // Try direct description field on response
        JsonNode directDesc = response.path("description");
        if (directDesc.isTextual() && !directDesc.asText().isBlank()) {
            return directDesc.asText();
        }

        return null;
    }
}
