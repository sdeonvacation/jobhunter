package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.LinkedInProfileService;
import dev.jobhunter.linkedin.LinkedInRateLimiter;
import dev.jobhunter.linkedin.ToolCategory;
import dev.jobhunter.model.Company;
import dev.jobhunter.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class CompanyEnrichmentService {

    private final LinkedInProfileService linkedInProfileService;
    private final CompanyRepository companyRepository;
    private final LinkedInRateLimiter rateLimiter;

    public CompanyEnrichmentService(LinkedInProfileService linkedInProfileService,
                                    CompanyRepository companyRepository,
                                    LinkedInRateLimiter rateLimiter) {
        this.linkedInProfileService = linkedInProfileService;
        this.companyRepository = companyRepository;
        this.rateLimiter = rateLimiter;
    }

    public CompanyEnrichmentResult enrich(UUID companyId) {
        Optional<Company> companyOpt = companyRepository.findById(companyId);
        if (companyOpt.isEmpty()) {
            return new CompanyEnrichmentResult(companyId, false, Set.of(), "Company not found");
        }

        Company company = companyOpt.get();

        if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
            return new CompanyEnrichmentResult(companyId, false, Set.of(), "Rate limit reached");
        }

        String linkedinUrl = company.getLinkedinUrl();
        if (linkedinUrl == null || linkedinUrl.isBlank()) {
            return new CompanyEnrichmentResult(companyId, false, Set.of(), "No LinkedIn URL configured");
        }

        try {
            LinkedInProfileService.ProfileData profileData = linkedInProfileService.getProfile(linkedinUrl);
            if (profileData == null) {
                return new CompanyEnrichmentResult(companyId, false, Set.of(), "LinkedIn returned no data");
            }

            Set<String> fieldsUpdated = new HashSet<>();

            if (profileData.company() != null && !profileData.company().isBlank()) {
                // Use headline as industry proxy if available
                if (profileData.headline() != null && !profileData.headline().isBlank()
                        && company.getIndustry() == null) {
                    company.setIndustry(profileData.headline());
                    fieldsUpdated.add("industry");
                }
            }

            if (profileData.skills() != null && !profileData.skills().isEmpty()) {
                String specialties = String.join(", ", profileData.skills());
                company.setSpecialties(specialties);
                fieldsUpdated.add("specialties");
            }

            company.setLinkedinEnrichedAt(LocalDateTime.now());
            fieldsUpdated.add("linkedinEnrichedAt");

            companyRepository.save(company);
            log.info("Enriched company '{}': updated fields {}", company.getName(), fieldsUpdated);

            return new CompanyEnrichmentResult(companyId, true, fieldsUpdated, null);
        } catch (Exception e) {
            log.error("Failed to enrich company '{}': {}", company.getName(), e.getMessage(), e);
            return new CompanyEnrichmentResult(companyId, false, Set.of(), e.getMessage());
        }
    }

    public List<CompanyEnrichmentResult> enrichBatch(List<UUID> companyIds, int maxPerRun) {
        List<CompanyEnrichmentResult> results = new ArrayList<>();
        int processed = 0;

        for (UUID companyId : companyIds) {
            if (processed >= maxPerRun) {
                break;
            }

            if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
                log.warn("Rate limit reached after processing {} companies, stopping batch", processed);
                results.add(new CompanyEnrichmentResult(companyId, false, Set.of(), "Rate limit reached"));
                break;
            }

            // Return token since enrich() will acquire its own
            CompanyEnrichmentResult result = enrich(companyId);
            results.add(result);

            if (result.success()) {
                processed++;
            }
        }

        log.info("Batch enrichment complete: {}/{} succeeded", 
                results.stream().filter(CompanyEnrichmentResult::success).count(), results.size());
        return results;
    }

    public record CompanyEnrichmentResult(
            UUID companyId,
            boolean success,
            Set<String> fieldsUpdated,
            String failureReason
    ) {}
}
