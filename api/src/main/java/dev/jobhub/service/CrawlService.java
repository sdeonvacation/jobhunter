package dev.jobhub.service;

import dev.jobhub.config.CrawlProperties;
import dev.jobhub.extraction.ExtractionResult;
import dev.jobhub.extraction.JobExtractor;
import dev.jobhub.extraction.JobExtractorRegistry;
import dev.jobhub.extraction.RawJobData;
import dev.jobhub.filter.FilterResult;
import dev.jobhub.filter.LanguageFilter;
import dev.jobhub.filter.RoleRelevanceFilter;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.CrawlStatus;
import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.model.enums.ExtractionStatus;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.scheduler.ScoringScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class CrawlService {

    private final CareerEndpointRepository endpointRepository;
    private final JobPostingRepository jobPostingRepository;
    private final JobExtractorRegistry extractorRegistry;
    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final CrawlProperties crawlProperties;
    private final ScoringScheduler scoringScheduler;

    public CrawlService(CareerEndpointRepository endpointRepository,
                        JobPostingRepository jobPostingRepository,
                        JobExtractorRegistry extractorRegistry,
                        LanguageFilter languageFilter,
                        RoleRelevanceFilter roleRelevanceFilter,
                        CrawlProperties crawlProperties,
                        ScoringScheduler scoringScheduler) {
        this.endpointRepository = endpointRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.extractorRegistry = extractorRegistry;
        this.languageFilter = languageFilter;
        this.roleRelevanceFilter = roleRelevanceFilter;
        this.crawlProperties = crawlProperties;
        this.scoringScheduler = scoringScheduler;
    }

    /**
     * Find all endpoints due for crawl and process each in isolation.
     * Returns summary stats: [endpointsCrawled, jobsFound, errors].
     */
    public int[] crawlAllDueEndpoints() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(crawlProperties.defaultFrequencyHours());
        List<CareerEndpoint> dueEndpoints = endpointRepository.findDueForCrawl(cutoff, crawlProperties.batchSize());

        log.info("Crawl cycle: {} endpoints due", dueEndpoints.size());

        int endpointsCrawled = 0;
        int totalJobs = 0;
        int errors = 0;

        for (CareerEndpoint endpoint : dueEndpoints) {
            try {
                int jobsFound = crawlEndpoint(endpoint);
                totalJobs += jobsFound;
                endpointsCrawled++;
            } catch (Exception e) {
                errors++;
                log.error("Crawl failed for endpoint [{}] (company: {}): {}",
                        endpoint.getId(), endpoint.getCompany().getName(), e.getMessage());
                markEndpointError(endpoint);
            }
        }

        log.info("Crawl cycle complete: {} endpoints, {} jobs found, {} errors",
                endpointsCrawled, totalJobs, errors);

        // Trigger scoring for new jobs immediately after crawl
        if (totalJobs > 0) {
            try {
                log.info("Triggering scoring for new jobs after crawl");
                scoringScheduler.scoreAllUnscored();
            } catch (Exception e) {
                log.error("Post-crawl scoring failed (jobs will be scored on next scheduler run)", e);
            }
        }

        return new int[]{endpointsCrawled, totalJobs, errors};
    }

    /**
     * Crawl a single endpoint: extract → filter → upsert/deactivate.
     */
    @Transactional
    public int crawlEndpoint(CareerEndpoint endpoint) {
        Optional<JobExtractor> extractorOpt = extractorRegistry.getExtractor(endpoint.getAtsType());
        if (extractorOpt.isEmpty()) {
            log.warn("No extractor for ATS type: {} (endpoint: {})", endpoint.getAtsType(), endpoint.getId());
            return 0;
        }

        JobExtractor extractor = extractorOpt.get();
        if (!extractor.canExtract(endpoint)) {
            log.warn("Extractor cannot handle endpoint [{}]: missing slug or misconfigured", endpoint.getId());
            return 0;
        }

        ExtractionResult result = extractor.extract(endpoint);
        return processExtractionResult(endpoint, result);
    }

    private int processExtractionResult(CareerEndpoint endpoint, ExtractionResult result) {
        if (result.status() == ExtractionStatus.ERROR) {
            endpoint.setLastCrawlStatus(CrawlStatus.ERROR);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpointRepository.save(endpoint);
            log.warn("Extraction error for endpoint [{}]: {}", endpoint.getId(), result.errorMessage());
            return 0;
        }

        if (result.status() == ExtractionStatus.EMPTY) {
            endpoint.setLastCrawlStatus(CrawlStatus.EMPTY);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpointRepository.save(endpoint);
            return 0;
        }

        // Track which external IDs were seen this crawl
        Set<String> seenExternalIds = new HashSet<>();
        int newJobsCount = 0;

        for (RawJobData rawJob : result.jobs()) {
            if (rawJob.externalId() == null) {
                continue;
            }
            seenExternalIds.add(rawJob.externalId());

            Optional<JobPosting> existingOpt = jobPostingRepository.findBySourceAndExternalId(
                    endpoint.getAtsType(), rawJob.externalId());

            if (existingOpt.isPresent()) {
                // Existing job: update lastCrawledAt
                JobPosting existing = existingOpt.get();
                existing.setLastCrawledAt(LocalDateTime.now());
                jobPostingRepository.save(existing);
            } else {
                // New job: apply language filter, then role relevance filter
                FilterResult filterResult = languageFilter.filter(rawJob.title(), rawJob.description());
                if (filterResult.decision() == FilterDecision.KEEP) {
                    FilterResult roleResult = roleRelevanceFilter.filter(rawJob.title());
                    if (roleResult.decision() == FilterDecision.SKIP) {
                        filterResult = roleResult;
                    }
                }

                JobPosting posting = buildJobPosting(endpoint, rawJob, filterResult);
                jobPostingRepository.save(posting);
                newJobsCount++;
            }
        }

        // Soft-delete jobs from this endpoint that weren't seen
        deactivateMissingJobs(endpoint, seenExternalIds);

        // Update endpoint status
        endpoint.setLastCrawlStatus(CrawlStatus.SUCCESS);
        endpoint.setLastCrawledAt(LocalDateTime.now());
        endpointRepository.save(endpoint);

        log.info("Endpoint [{}]: {} total, {} new jobs", endpoint.getId(), result.totalFound(), newJobsCount);
        return newJobsCount;
    }

    private JobPosting buildJobPosting(CareerEndpoint endpoint, RawJobData rawJob, FilterResult filterResult) {
        return JobPosting.builder()
                .source(endpoint.getAtsType())
                .endpoint(endpoint)
                .externalId(rawJob.externalId())
                .title(rawJob.title())
                .company(endpoint.getCompany())
                .location(rawJob.location())
                .description(rawJob.description())
                .applyUrl(rawJob.applyUrl())
                .postedDate(rawJob.postedDate())
                .discoveredDate(LocalDate.now())
                .salaryMin(rawJob.salaryMin())
                .salaryMax(rawJob.salaryMax())
                .salaryCurrency(rawJob.salaryCurrency())
                .rawContent(rawJob.rawJson() != null ? Map.of("raw", rawJob.rawJson()) : null)
                .languageFilter(filterResult.decision())
                .filterReason(filterResult.reason())
                .isActive(true)
                .lastCrawledAt(LocalDateTime.now())
                .build();
    }

    private void deactivateMissingJobs(CareerEndpoint endpoint, Set<String> seenExternalIds) {
        List<JobPosting> activeJobs = jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId());
        LocalDateTime now = LocalDateTime.now();

        for (JobPosting job : activeJobs) {
            if (!seenExternalIds.contains(job.getExternalId())) {
                job.setActive(false);
                job.setDeactivatedAt(now);
                jobPostingRepository.save(job);
            }
        }
    }

    private void markEndpointError(CareerEndpoint endpoint) {
        try {
            endpoint.setLastCrawlStatus(CrawlStatus.ERROR);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpointRepository.save(endpoint);
        } catch (Exception e) {
            log.error("Failed to mark endpoint error status for [{}]", endpoint.getId(), e);
        }
    }
}
