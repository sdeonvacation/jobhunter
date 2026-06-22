package dev.jobhunter.service;

import dev.jobhunter.linkedin.LinkedInDescriptionEnricher;
import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import dev.jobhunter.strategy.ats.SmartRecruitersStrategy;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.util.LocationCountryParser;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.LocationFilter;
import dev.jobhunter.filter.RoleRelevanceFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.people.crawl.PostCrawlPipeline;
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
    private final StrategyRegistry strategyRegistry;
    private final SmartRecruitersStrategy smartRecruitersStrategy;
    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final LocationFilter locationFilter;
    private final YoeFilter yoeFilter;
    private final DeduplicationFilter deduplicationFilter;
    private final VisaSponsorshipFilter visaSponsorshipFilter;
    private final ScoringScheduler scoringScheduler;
    private final PostCrawlPipeline postCrawlPipeline;
    private final Optional<LinkedInDescriptionEnricher> linkedInDescriptionEnricher;

    public CrawlService(CareerEndpointRepository endpointRepository,
                        JobPostingRepository jobPostingRepository,
                        StrategyRegistry strategyRegistry,
                        SmartRecruitersStrategy smartRecruitersStrategy,
                        LanguageFilter languageFilter,
                        RoleRelevanceFilter roleRelevanceFilter,
                        LocationFilter locationFilter,
                        YoeFilter yoeFilter,
                        DeduplicationFilter deduplicationFilter,
                        VisaSponsorshipFilter visaSponsorshipFilter,
                        ScoringScheduler scoringScheduler,
                        PostCrawlPipeline postCrawlPipeline,
                        Optional<LinkedInDescriptionEnricher> linkedInDescriptionEnricher) {
        this.endpointRepository = endpointRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.strategyRegistry = strategyRegistry;
        this.smartRecruitersStrategy = smartRecruitersStrategy;
        this.languageFilter = languageFilter;
        this.roleRelevanceFilter = roleRelevanceFilter;
        this.locationFilter = locationFilter;
        this.yoeFilter = yoeFilter;
        this.deduplicationFilter = deduplicationFilter;
        this.visaSponsorshipFilter = visaSponsorshipFilter;
        this.scoringScheduler = scoringScheduler;
        this.postCrawlPipeline = postCrawlPipeline;
        this.linkedInDescriptionEnricher = linkedInDescriptionEnricher;
    }

    /**
     * Find all endpoints due for crawl and process each in isolation.
     * Returns summary stats: [endpointsCrawled, jobsFound, errors].
     */
    public int[] crawlAllDueEndpoints() {
        List<CareerEndpoint> endpoints = endpointRepository.findAllActiveNonCustom();

        log.info("Crawl cycle: {} endpoints", endpoints.size());

        int endpointsCrawled = 0;
        int totalJobs = 0;
        int errors = 0;

        for (CareerEndpoint endpoint : endpoints) {
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

        // Backfill descriptions for SmartRecruiters KEEP jobs (fast: only ~50 jobs)
        try {
            backfillSmartRecruitersDescriptions();
        } catch (Exception e) {
            log.error("SmartRecruiters description backfill failed", e);
        }

        // Enrich LinkedIn descriptions and re-score (descriptions arrive after initial scoring)
        try {
            linkedInDescriptionEnricher.ifPresent(enricher -> {
                // enrich() requires source=LINKEDIN and created>0 to proceed
                enricher.enrich(JobSource.LINKEDIN, 1);
                // Re-score jobs that now have descriptions
                log.info("Re-scoring after LinkedIn description enrichment");
                scoringScheduler.scoreAllUnscored();
            });
        } catch (Exception e) {
            log.error("LinkedIn description enrichment failed", e);
        }

        return new int[]{endpointsCrawled, totalJobs, errors};
    }

    /**
     * Crawl a single endpoint: extract → filter → upsert/deactivate.
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
        FetchResult result = strategy.fetch(FetchContext.forEndpoint(endpoint));
        return processFetchResult(endpoint, result);
    }

    private int processFetchResult(CareerEndpoint endpoint, FetchResult result) {
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
                    // Re-run filters now that description is available
                    if (existing.getLanguageFilter() == FilterDecision.KEEP) {
                        FilterResult langResult = languageFilter.filter(existing.getTitle(), rawJob.description());
                        if (langResult.decision() == FilterDecision.SKIP) {
                            existing.setLanguageFilter(FilterDecision.SKIP);
                            existing.setFilterReason(langResult.reason());
                        } else {
                            Integer yoe = yoeFilter.extractYoe(rawJob.description());
                            existing.setRequiredYoe(yoe);
                            FilterResult yoeResult = yoeFilter.filter(yoe);
                            if (yoeResult.decision() == FilterDecision.SKIP) {
                                existing.setLanguageFilter(FilterDecision.SKIP);
                                existing.setFilterReason(yoeResult.reason());
                            }
                        }
                    }
                }
                if (existing.getApplyUrl() == null && rawJob.applyUrl() != null) {
                    existing.setApplyUrl(rawJob.applyUrl());
                }
                jobPostingRepository.save(existing);
            } else {
                // New job: apply filter cascade (language → role → location → visa → yoe → dedup)
                String companyName = endpoint.getCompany() != null ? endpoint.getCompany().getName() : "";
                String fingerprint = deduplicationFilter.generateFingerprint(rawJob.title(), companyName, rawJob.location());

                FilterResult filterResult = languageFilter.filter(rawJob.title(), rawJob.description());
                VisaFilterResult visaResult = null;
                if (filterResult.decision() == FilterDecision.KEEP) {
                    FilterResult roleResult = roleRelevanceFilter.filter(rawJob.title());
                    if (roleResult.decision() == FilterDecision.SKIP) {
                        filterResult = roleResult;
                    } else {
                        FilterResult locationResult = locationFilter.filter(rawJob.location());
                        if (locationResult.decision() == FilterDecision.SKIP) {
                            filterResult = locationResult;
                        } else {
                            // Visa sponsorship filter (direct endpoints = not aggregator)
                            visaResult = visaSponsorshipFilter.filter(
                                    rawJob.location(), rawJob.description(), false);
                            if (visaResult.decision() == FilterDecision.SKIP) {
                                filterResult = FilterResult.skip(visaResult.reason());
                            } else {
                                // YOE filter
                                Integer yoe = yoeFilter.extractYoe(rawJob.description());
                                FilterResult yoeResult = yoeFilter.filter(yoe);
                                if (yoeResult.decision() == FilterDecision.SKIP) {
                                    filterResult = yoeResult;
                                } else {
                                    // Deduplication: check if same title+company already exists
                                    Optional<JobPosting> duplicate = jobPostingRepository
                                            .findFirstByFingerprintAndLanguageFilter(fingerprint, FilterDecision.KEEP);
                                    if (duplicate.isPresent()) {
                                        filterResult = FilterResult.skip("duplicate of " + duplicate.get().getSource());
                                    }
                                }
                            }
                        }
                    }
                }

                Integer yoe = yoeFilter.extractYoe(rawJob.description());
                JobPosting posting = buildJobPosting(endpoint, rawJob, filterResult);
                posting.setRequiredYoe(yoe);
                posting.setFingerprint(fingerprint);
                // Set visa sponsorship status if visa filter was evaluated
                if (visaResult != null) {
                    posting.setVisaSponsorship(visaResult.visaSponsorship());
                }
                jobPostingRepository.save(posting);

                // Run post-crawl hooks (poster extraction, etc.) for KEEP jobs
                if (filterResult.decision() == FilterDecision.KEEP) {
                    Map<String, Object> rawContent = posting.getRawContent() != null ? posting.getRawContent() : Map.of();
                    postCrawlPipeline.run(posting, rawJob.rawJson(), rawContent);
                }

                newJobsCount++;
            }
        }

        // Soft-delete jobs from this endpoint that weren't seen
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

    private JobPosting buildJobPosting(CareerEndpoint endpoint, RawAggregatorJob rawJob, FilterResult filterResult) {
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

    /**
     * Backfill descriptions for SmartRecruiters KEEP jobs that have no description.
     * Only fetches details for filtered (visible) jobs, not all 1000+.
     * After backfill, re-runs language filter — jobs with German descriptions get marked SKIP.
     *
     * @return int array: [0] = descriptions filled, [1] = language-filtered (marked SKIP)
     */
    @Transactional
    public int[] backfillSmartRecruitersDescriptions() {
        List<JobPosting> jobsWithoutDesc = jobPostingRepository
                .findBySourceAndLanguageFilterAndDescriptionIsNull(
                        JobSource.SMARTRECRUITERS,
                        FilterDecision.KEEP);

        if (jobsWithoutDesc.isEmpty()) {
            return new int[]{0, 0};
        }

        log.info("Backfilling descriptions for {} SmartRecruiters KEEP jobs", jobsWithoutDesc.size());
        int filled = 0;
        int filtered = 0;

        for (JobPosting job : jobsWithoutDesc) {
            String slug = job.getEndpoint() != null ? job.getEndpoint().getAtsSlug() : null;
            if (slug == null) continue;

            String description = smartRecruitersStrategy.fetchDescription(slug, job.getExternalId());
            if (description != null) {
                job.setDescription(description);
                filled++;

                // Re-run language filter now that description is available
                FilterResult filterResult = languageFilter.filter(job.getTitle(), description);
                if (filterResult.decision() == FilterDecision.SKIP) {
                    job.setLanguageFilter(FilterDecision.SKIP);
                    job.setFilterReason(filterResult.reason());
                    filtered++;
                    log.debug("Post-backfill language filter SKIP: job={} reason={}",
                            job.getExternalId(), filterResult.reason());
                } else {
                    // Re-run YOE filter now that description is available
                    Integer yoe = yoeFilter.extractYoe(description);
                    job.setRequiredYoe(yoe);
                    FilterResult yoeResult = yoeFilter.filter(yoe);
                    if (yoeResult.decision() == FilterDecision.SKIP) {
                        job.setLanguageFilter(FilterDecision.SKIP);
                        job.setFilterReason(yoeResult.reason());
                        filtered++;
                        log.debug("Post-backfill YOE filter SKIP: job={} yoe={} reason={}",
                                job.getExternalId(), yoe, yoeResult.reason());
                    }
                }

                jobPostingRepository.save(job);
            }
        }

        log.info("Backfilled {}/{} SmartRecruiters descriptions, {} language-filtered",
                filled, jobsWithoutDesc.size(), filtered);
        return new int[]{filled, filtered};
    }
}
