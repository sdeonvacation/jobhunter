package dev.jobhunter.people.scheduler;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.service.CompanyEnrichmentService;
import dev.jobhunter.people.service.HiringVelocityCalculator;
import dev.jobhunter.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weekly scheduler for company LinkedIn enrichment and hiring velocity calculation.
 */
@Slf4j
@Component
public class CompanyEnrichmentScheduler {

    private final CompanyEnrichmentService enrichmentService;
    private final HiringVelocityCalculator velocityCalculator;
    private final CompanyRepository companyRepository;
    private final int batchSize;

    public CompanyEnrichmentScheduler(CompanyEnrichmentService enrichmentService,
                                      HiringVelocityCalculator velocityCalculator,
                                      CompanyRepository companyRepository,
                                      @Value("${people.enrichment.batch-size:10}") int batchSize) {
        this.enrichmentService = enrichmentService;
        this.velocityCalculator = velocityCalculator;
        this.companyRepository = companyRepository;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${people.enrichment.schedule:0 0 3 * * SUN}")
    public void runWeeklyEnrichment() {
        log.info("Starting weekly company enrichment (batch size: {})", batchSize);
        try {
            List<UUID> companyIds = findCompaniesNeedingEnrichment();
            var results = enrichmentService.enrichBatch(companyIds, batchSize);
            long successCount = results.stream()
                    .filter(CompanyEnrichmentService.CompanyEnrichmentResult::success)
                    .count();
            log.info("Weekly enrichment complete: {}/{} succeeded", successCount, results.size());
        } catch (Exception e) {
            log.error("Weekly enrichment failed: {}", e.getMessage(), e);
        }

        try {
            Map<UUID, Integer> velocities = velocityCalculator.calculateAll();
            log.info("Hiring velocity calculated for {} companies", velocities.size());
        } catch (Exception e) {
            log.error("Hiring velocity calculation failed: {}", e.getMessage(), e);
        }
    }

    private List<UUID> findCompaniesNeedingEnrichment() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusDays(30);
        return companyRepository.findByIsActiveTrue().stream()
                .filter(company -> company.getLinkedinUrl() != null && !company.getLinkedinUrl().isBlank())
                .filter(company -> company.getLinkedinEnrichedAt() == null
                        || company.getLinkedinEnrichedAt().isBefore(staleThreshold))
                .map(Company::getId)
                .toList();
    }
}
