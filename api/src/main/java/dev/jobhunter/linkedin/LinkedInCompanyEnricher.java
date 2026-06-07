package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.model.Company;
import dev.jobhunter.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInCompanyEnricher {

    private static final int HIRING_SURGE_KEYWORD_THRESHOLD = 5;
    private static final List<String> HIRING_KEYWORDS = List.of(
            "hiring", "join our team", "open position", "we're looking",
            "new role", "come work", "job opening", "growing team"
    );

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final CompanyRepository companyRepository;
    private final LinkedInMcpProperties mcpProperties;

    public LinkedInCompanyEnricher(HttpMcpClient httpMcpClient,
                                   LinkedInRateLimiter rateLimiter,
                                   CompanyRepository companyRepository,
                                   LinkedInMcpProperties mcpProperties) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.companyRepository = companyRepository;
        this.mcpProperties = mcpProperties;
    }

    /**
     * Enrich a company with LinkedIn profile data and recent posts.
     * Best-effort: errors are logged but not propagated.
     */
    public CompletableFuture<Void> enrich(Company company) {
        return CompletableFuture.runAsync(() -> {
            try {
                enrichCompanyProfile(company);
                enrichRecentPosts(company);
                company.setLinkedinEnrichedAt(LocalDateTime.now());
                companyRepository.save(company);
                log.info("Enriched company '{}' with LinkedIn data", company.getName());
            } catch (Exception e) {
                log.error("Failed to enrich company '{}': {}", company.getName(), e.getMessage());
            }
        });
    }

    /**
     * Enrich a batch of companies with configurable delay between each.
     */
    public void enrichBatch(List<Company> companies) {
        int batchSize = mcpProperties.enrichment().batchSize();
        long delayMs = mcpProperties.enrichment().delayBetweenMs();

        int count = Math.min(companies.size(), batchSize);

        for (int i = 0; i < count; i++) {
            Company company = companies.get(i);
            try {
                enrichCompanyProfile(company);
                enrichRecentPosts(company);
                company.setLinkedinEnrichedAt(LocalDateTime.now());
                companyRepository.save(company);
                log.info("Batch enriched company '{}' ({}/{})", company.getName(), i + 1, count);

                if (i < count - 1 && delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch enrichment interrupted at {}/{}", i + 1, count);
                break;
            } catch (Exception e) {
                log.error("Failed to batch-enrich company '{}': {}", company.getName(), e.getMessage());
            }
        }
    }

    /**
     * Detect if a company has a hiring surge based on recent posts containing job-related keywords.
     */
    public boolean detectHiringSurge(UUID companyId) {
        return companyRepository.findById(companyId)
                .map(company -> {
                    String summary = company.getRecentPostsSummary();
                    if (summary == null || summary.isBlank()) {
                        return false;
                    }
                    String lowerSummary = summary.toLowerCase();
                    long matchCount = HIRING_KEYWORDS.stream()
                            .filter(lowerSummary::contains)
                            .count();
                    return matchCount >= HIRING_SURGE_KEYWORD_THRESHOLD;
                })
                .orElse(false);
    }

    private void enrichCompanyProfile(Company company) {
        if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
            log.warn("Rate limit reached for PROFILE, skipping company profile enrichment for '{}'", company.getName());
            return;
        }

        String identifier = company.getLinkedinUrl() != null ? company.getLinkedinUrl() : company.getName();
        Map<String, Object> params = Map.of("company", identifier);

        JsonNode response = httpMcpClient.callTool("get_company_profile", params);

        String industry = getTextOrNull(response, "industry");
        if (industry != null) {
            company.setIndustry(industry);
        }

        JsonNode employeeNode = response.path("employee_count");
        if (employeeNode.isInt()) {
            company.setEmployeeCount(employeeNode.asInt());
        } else if (employeeNode.isTextual()) {
            try {
                company.setEmployeeCount(Integer.parseInt(employeeNode.asText().replaceAll("[^0-9]", "")));
            } catch (NumberFormatException ignored) {
                // Skip unparseable employee counts
            }
        }

        String specialties = getTextOrNull(response, "specialties", "tagline");
        if (specialties != null) {
            company.setSpecialties(specialties);
        }

        String linkedinUrl = getTextOrNull(response, "url", "linkedin_url", "profile_url");
        if (linkedinUrl != null && company.getLinkedinUrl() == null) {
            company.setLinkedinUrl(linkedinUrl);
        }
    }

    private void enrichRecentPosts(Company company) {
        if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
            log.warn("Rate limit reached for PROFILE, skipping posts enrichment for '{}'", company.getName());
            return;
        }

        String identifier = company.getLinkedinUrl() != null ? company.getLinkedinUrl() : company.getName();
        Map<String, Object> params = Map.of("company", identifier);

        try {
            JsonNode response = httpMcpClient.callTool("get_company_posts", params);
            String summary = buildPostsSummary(response);
            if (summary != null && !summary.isBlank()) {
                company.setRecentPostsSummary(summary);
            }
        } catch (McpClientException e) {
            log.debug("Could not fetch posts for '{}': {}", company.getName(), e.getMessage());
        }
    }

    private String buildPostsSummary(JsonNode response) {
        JsonNode posts = response.path("posts");
        if (!posts.isArray()) {
            posts = response.isArray() ? response : response.path("results");
        }

        if (!posts.isArray() || posts.isEmpty()) {
            return null;
        }

        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (JsonNode post : posts) {
            if (count >= 5) break;
            String text = getTextOrNull(post, "text", "content", "summary");
            if (text != null) {
                summary.append(text, 0, Math.min(text.length(), 200)).append("\n---\n");
                count++;
            }
        }

        return summary.isEmpty() ? null : summary.toString();
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
}
