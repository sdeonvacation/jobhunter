package dev.jobhunter.service;

import dev.jobhunter.ingestion.BackfillPostProcessor;
import dev.jobhunter.ingestion.DescriptionBackfiller;
import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.filter.FilterChainResult;
import dev.jobhunter.filter.JobFilterChain;
import dev.jobhunter.filter.RawJobInput;
import dev.jobhunter.util.LocationCountryParser;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.people.crawl.PostCrawlPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class CrawlService {

    @Value("${crawl.concurrency:20}")
    private int crawlConcurrency;

    private final CareerEndpointRepository endpointRepository;
    private final JobPostingRepository jobPostingRepository;
    private final StrategyRegistry strategyRegistry;
    private final JobFilterChain jobFilterChain;
    private final DeduplicationFilter deduplicationFilter;
    private final DescriptionFilterChain descriptionFilterChain;
    private final List<DescriptionBackfiller> descriptionBackfillers;
    private final List<BackfillPostProcessor> backfillPostProcessors;
    private final ScoringService scoringService;
    private final PostCrawlPipeline postCrawlPipeline;

    public CrawlService(CareerEndpointRepository endpointRepository,
                        JobPostingRepository jobPostingRepository,
                        StrategyRegistry strategyRegistry,
                        JobFilterChain jobFilterChain,
                        DeduplicationFilter deduplicationFilter,
                        DescriptionFilterChain descriptionFilterChain,
                        List<DescriptionBackfiller> descriptionBackfillers,
                        List<BackfillPostProcessor> backfillPostProcessors,
                        ScoringService scoringService,
                        PostCrawlPipeline postCrawlPipeline) {
        this.endpointRepository = endpointRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.strategyRegistry = strategyRegistry;
        this.jobFilterChain = jobFilterChain;
        this.deduplicationFilter = deduplicationFilter;
        this.descriptionFilterChain = descriptionFilterChain;
        this.descriptionBackfillers = descriptionBackfillers;
        this.backfillPostProcessors = backfillPostProcessors;
        this.scoringService = scoringService;
        this.postCrawlPipeline = postCrawlPipeline;
    }

    /**
     * Find all endpoints due for crawl and process each in parallel via virtual threads.
     * A Semaphore caps concurrency at {@code crawl.concurrency} (default 20).
     * Returns summary stats: [endpointsCrawled, jobsFound, errors].
     */
    public int[] crawlAllDueEndpoints() {
        List<CareerEndpoint> endpoints = endpointRepository.findAllActiveNonCustom();
        log.info("Crawl cycle: {} endpoints (concurrency: {})", endpoints.size(), crawlConcurrency);

        var endpointsCrawled = new AtomicInteger(0);
        var totalJobs = new AtomicInteger(0);
        var errors = new AtomicInteger(0);
        var semaphore = new Semaphore(crawlConcurrency);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (CareerEndpoint endpoint : endpoints) {
                executor.submit(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        int jobsFound = crawlEndpoint(endpoint);
                        totalJobs.addAndGet(jobsFound);
                        endpointsCrawled.incrementAndGet();
                        if (jobsFound > 0) {
                            try {
                                scoringService.scoreJobsForEndpoint(endpoint.getId());
                            } catch (Exception e) {
                                log.error("Post-crawl scoring failed for endpoint [{}]", endpoint.getId(), e);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        log.error("Crawl failed for endpoint [{}] (company: {}): {}",
                                endpoint.getId(),
                                endpoint.getCompany() != null ? endpoint.getCompany().getName() : "unknown",
                                e.getMessage(), e);
                        markEndpointError(endpoint);
                    } finally {
                        semaphore.release();
                    }
                });
            }
        } // executor.close() shuts down and awaits all submitted tasks

        log.info("Crawl cycle complete: {} endpoints, {} jobs found, {} errors",
                endpointsCrawled.get(), totalJobs.get(), errors.get());

        // Backfill descriptions (sequential after all crawls)
        descriptionBackfillers.forEach(backfiller -> {
            try {
                backfiller.backfill();
            } catch (Exception e) {
                log.error("Description backfill failed [{}]: {}", backfiller.getClass().getSimpleName(), e.getMessage());
            }
        });
        backfillPostProcessors.forEach(processor -> {
            try {
                processor.process();
            } catch (Exception e) {
                log.error("Backfill post-processor failed [{}]: {}", processor.getClass().getSimpleName(), e.getMessage());
            }
        });

        return new int[]{endpointsCrawled.get(), totalJobs.get(), errors.get()};
    }

    /**
     * Crawl a single endpoint: fetch then persist results in a transaction.
     * Spring acquires a DB connection lazily — only at the first save/query inside this method,
     * which is after fetch() completes. No connection is held during the HTTP call.
     */
    @Transactional
    public int crawlEndpoint(CareerEndpoint endpoint) {
        Optional<FetchStrategy> strategyOpt = strategyRegistry.getStrategy(endpoint.getAtsType());
        if (strategyOpt.isEmpty()) {
            log.warn("No strategy for ATS type: {} (endpoint: {})", endpoint.getAtsType(), endpoint.getId());
            endpoint.setLastCrawlStatus(CrawlStatus.SKIPPED);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpointRepository.save(endpoint);
            return 0;
        }

        FetchStrategy strategy = strategyOpt.get();
        FetchResult result = strategy.fetch(FetchContext.forEndpoint(endpoint)); // HTTP fetch — no DB connection held yet

        if (result.status() == ExtractionStatus.RATE_LIMITED) {
            endpoint.setLastCrawlStatus(CrawlStatus.RATE_LIMITED);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpoint.setLastErrorMessage(result.errorMessage());
            // Don't increment consecutiveErrors — transient rate limit, not a permanent failure
            endpointRepository.save(endpoint);
            log.warn("Rate limited for endpoint [{}] (company: {}), will retry next cycle",
                    endpoint.getId(),
                    endpoint.getCompany() != null ? endpoint.getCompany().getName() : "unknown");
            return 0;
        }

        if (result.status() == ExtractionStatus.ERROR) {
            endpoint.setLastCrawlStatus(CrawlStatus.ERROR);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpoint.setLastErrorMessage(result.errorMessage());
            int newErrorCount = endpoint.getConsecutiveErrors() + 1;
            endpoint.setConsecutiveErrors(newErrorCount);
            if (newErrorCount >= 10) {
                endpoint.setActive(false);
                log.warn("Auto-deactivating endpoint [{}] (company: {}) after {} consecutive errors",
                        endpoint.getId(),
                        endpoint.getCompany() != null ? endpoint.getCompany().getName() : "unknown",
                        newErrorCount);
            }
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

        // SUCCESS status with no jobs: treat as EMPTY to avoid deactivating all existing postings
        if (result.jobs().isEmpty()) {
            endpoint.setLastCrawlStatus(CrawlStatus.EMPTY);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            endpointRepository.save(endpoint);
            return 0;
        }

        // Track which external IDs were seen this crawl
        Set<String> seenExternalIds = new HashSet<>();
        int newJobsCount = 0;

        for (RawAggregatorJob rawJob : result.jobs()) {
            if (rawJob.externalId() == null) {
                continue;
            }
            seenExternalIds.add(rawJob.externalId());

            Optional<JobPosting> existingOpt = jobPostingRepository.findBySourceAndExternalId(
                    JobSource.fromAtsType(endpoint.getAtsType()), rawJob.externalId());

            if (existingOpt.isPresent()) {
                // Existing job: update lastCrawledAt + backfill missing description
                JobPosting existing = existingOpt.get();
                existing.setLastCrawledAt(LocalDateTime.now());
                if (existing.getDescription() == null && rawJob.description() != null) {
                    existing.setDescription(rawJob.description());
                    descriptionFilterChain.refilter(existing);
                }
                if (existing.getApplyUrl() == null && rawJob.applyUrl() != null) {
                    existing.setApplyUrl(rawJob.applyUrl());
                }
                jobPostingRepository.save(existing);
            } else {
                // New job: apply unified filter chain
                String companyName = endpoint.getCompany() != null ? endpoint.getCompany().getName() : "";
                String fingerprint = deduplicationFilter.generateFingerprint(
                        rawJob.title(), companyName, rawJob.location());
                RawJobInput filterInput = new RawJobInput(
                        rawJob.title(), rawJob.description(), rawJob.location(), companyName);
                FilterChainResult chainResult = jobFilterChain.apply(filterInput, false, false);

                JobPosting posting = buildJobPosting(endpoint, rawJob, chainResult, fingerprint);
                jobPostingRepository.save(posting);

                // Run post-crawl hooks for KEEP jobs
                if (chainResult.decision() == FilterDecision.KEEP) {
                    Map<String, Object> rawContent = posting.getRawContent() != null
                            ? posting.getRawContent() : Map.of();
                    postCrawlPipeline.run(posting, rawJob.rawJson(), rawContent);
                }

                newJobsCount++;
            }
        }

        // Soft-delete jobs from this endpoint that weren't seen this crawl
        deactivateMissingJobs(endpoint, seenExternalIds);

        // Update endpoint status
        endpoint.setLastCrawlStatus(CrawlStatus.SUCCESS);
        endpoint.setLastCrawledAt(LocalDateTime.now());
        endpoint.setConsecutiveErrors(0);
        endpoint.setLastErrorMessage(null);
        endpointRepository.save(endpoint);

        log.info("Endpoint [{}]: {} total, {} new jobs", endpoint.getId(), result.totalFound(), newJobsCount);
        return newJobsCount;
    }

    private JobPosting buildJobPosting(CareerEndpoint endpoint, RawAggregatorJob rawJob,
                                       FilterChainResult chainResult, String fingerprint) {
        return JobPosting.builder()
                .source(JobSource.fromAtsType(endpoint.getAtsType()))
                .endpoint(endpoint)
                .externalId(rawJob.externalId())
                .title(rawJob.title())
                .company(endpoint.getCompany())
                .location(rawJob.location())
                .locationCountry(LocationCountryParser.extractCountry(rawJob.location()))
                .locationCity(LocationCountryParser.extractCity(rawJob.location()))
                .description(rawJob.description())
                .applyUrl(rawJob.applyUrl())
                .postedDate(rawJob.postedDate())
                .discoveredDate(LocalDate.now())
                .salaryMin(rawJob.salaryMin())
                .salaryMax(rawJob.salaryMax())
                .salaryCurrency(rawJob.salaryCurrency())
                .rawContent(rawJob.rawJson() != null ? Map.of("raw", rawJob.rawJson()) : null)
                .languageFilter(chainResult.decision())
                .filterReason(chainResult.reason())
                .requiredYoe(chainResult.extractedYoe())
                .fingerprint(fingerprint)
                .visaSponsorship(chainResult.visaSponsorship())
                .isActive(true)
                .lastCrawledAt(LocalDateTime.now())
                .build();
    }

    private void deactivateMissingJobs(CareerEndpoint endpoint, Set<String> seenExternalIds) {
        if (seenExternalIds.isEmpty()) {
            // No jobs seen; deactivate all active jobs for this endpoint individually
            List<JobPosting> activeJobs = jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId());
            LocalDateTime now = LocalDateTime.now();
            activeJobs.forEach(job -> {
                job.setActive(false);
                job.setDeactivatedAt(now);
            });
            jobPostingRepository.saveAll(activeJobs);
            return;
        }
        jobPostingRepository.bulkDeactivateByEndpointExcluding(endpoint.getId(), seenExternalIds, LocalDateTime.now());
    }

    private void markEndpointError(CareerEndpoint endpoint) {
        try {
            endpoint.setLastCrawlStatus(CrawlStatus.ERROR);
            endpoint.setLastCrawledAt(LocalDateTime.now());
            int newErrorCount = endpoint.getConsecutiveErrors() + 1;
            endpoint.setConsecutiveErrors(newErrorCount);
            if (newErrorCount >= 10) {
                endpoint.setActive(false);
                log.warn("Auto-deactivating endpoint [{}] after {} consecutive errors", endpoint.getId(), newErrorCount);
            }
            endpointRepository.save(endpoint);
        } catch (Exception e) {
            log.error("Failed to mark endpoint error status for [{}]", endpoint.getId(), e);
        }
    }
}
