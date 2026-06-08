package dev.jobhunter.ingestion;

import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.LocationFilter;
import dev.jobhunter.filter.RoleRelevanceFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.CompanyStatus;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.source.SourceConfig;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class AggregatorIngestionServiceImpl implements AggregatorIngestionService {

    private final JobPostingRepository jobPostingRepository;
    private final CompanyRepository companyRepository;
    private final AggregatorRunRepository aggregatorRunRepository;
    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final LocationFilter locationFilter;
    private final YoeFilter yoeFilter;
    private final DeduplicationFilter deduplicationFilter;

    public AggregatorIngestionServiceImpl(JobPostingRepository jobPostingRepository,
                                          CompanyRepository companyRepository,
                                          AggregatorRunRepository aggregatorRunRepository,
                                          LanguageFilter languageFilter,
                                          RoleRelevanceFilter roleRelevanceFilter,
                                          LocationFilter locationFilter,
                                          YoeFilter yoeFilter,
                                          DeduplicationFilter deduplicationFilter) {
        this.jobPostingRepository = jobPostingRepository;
        this.companyRepository = companyRepository;
        this.aggregatorRunRepository = aggregatorRunRepository;
        this.languageFilter = languageFilter;
        this.roleRelevanceFilter = roleRelevanceFilter;
        this.locationFilter = locationFilter;
        this.yoeFilter = yoeFilter;
        this.deduplicationFilter = deduplicationFilter;
    }

    @Override
    @Transactional
    public IngestionStats ingest(SourceConfig source) {
        long startMs = System.currentTimeMillis();
        int created = 0, enriched = 0, filtered = 0, duplicates = 0, errors = 0;

        FetchResult result;
        try {
            result = source.strategy().fetch(source.buildContext());
        } catch (Exception e) {
            log.error("Fetch failed for source [{}]: {}", source.name(), e.getMessage(), e);
            long elapsed = System.currentTimeMillis() - startMs;
            updateAggregatorRun(source.name(), "ERROR", 0, 0, 0, 0, 1, elapsed, e.getMessage());
            return new IngestionStats(source.name(), 0, 0, 0, 0, 0, 1, elapsed);
        }

        if (result.jobs().isEmpty()) {
            long elapsed = System.currentTimeMillis() - startMs;
            int fetchErrors = result.status() == ExtractionStatus.ERROR ? 1 : 0;
            updateAggregatorRun(source.name(), result.status().name(), 0, 0, 0, 0, fetchErrors, elapsed, result.errorMessage());
            return new IngestionStats(source.name(), 0, 0, 0, 0, 0, fetchErrors, elapsed);
        }

        JobSource jobSource = source.sourceType();

        for (RawAggregatorJob job : result.jobs()) {
            try {
                // Skip if exact source+externalId already exists
                if (jobPostingRepository.findBySourceAndExternalId(jobSource, job.externalId()).isPresent()) {
                    duplicates++;
                    continue;
                }

                // Generate fingerprint for cross-source dedup
                String companyName = job.companyName() != null ? job.companyName() : "";
                String fingerprint = deduplicationFilter.generateFingerprint(job.title(), companyName, job.location());

                // Check if an ATS job with same fingerprint exists — enrich it
                // Only attempt ATS matching when company name is known (avoids false positives)
                if (job.companyName() != null && !job.companyName().isBlank()) {
                    Optional<JobPosting> atsMatch = jobPostingRepository.findAtsJobByFingerprint(fingerprint, JobSource.aggregators());
                    if (atsMatch.isPresent()) {
                        JobPosting existing = atsMatch.get();
                        existing.getExternalLinks().put(source.name(), job.applyUrl());
                        jobPostingRepository.save(existing);
                        enriched++;
                        continue;
                    }
                }

                // Apply filter cascade
                FilterResult filterResult = applyFilters(job);
                if (filterResult.decision() == FilterDecision.SKIP) {
                    filtered++;
                    continue;
                }

                // Resolve company
                Company company = resolveCompany(job.companyName(), source);

                // Build and save JobPosting
                Integer yoe = yoeFilter.extractYoe(job.description());
                JobPosting posting = JobPosting.builder()
                        .source(jobSource)
                        .externalId(job.externalId())
                        .title(job.title())
                        .company(company)
                        .location(job.location())
                        .description(job.description())
                        .applyUrl(job.applyUrl())
                        .fingerprint(fingerprint)
                        .postedDate(job.postedDate())
                        .discoveredDate(LocalDate.now())
                        .salaryMin(job.salaryMin())
                        .salaryMax(job.salaryMax())
                        .salaryCurrency(job.salaryCurrency())
                        .requiredYoe(yoe)
                        .isActive(true)
                        .languageFilter(FilterDecision.KEEP)
                        .build();
                jobPostingRepository.save(posting);
                created++;
            } catch (Exception e) {
                log.warn("Error processing job [{}] from source [{}]: {}",
                        job.externalId(), source.name(), e.getMessage());
                errors++;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        String status = errors > 0 && created == 0 ? "ERROR" : "SUCCESS";
        updateAggregatorRun(source.name(), status, result.jobs().size(), created, enriched, filtered, errors, elapsedMs, null);

        log.info("Ingestion [{}]: fetched={}, created={}, enriched={}, filtered={}, duplicates={}, errors={}, elapsed={}ms",
                source.name(), result.jobs().size(), created, enriched, filtered, duplicates, errors, elapsedMs);

        return new IngestionStats(source.name(), result.jobs().size(), enriched, created, filtered, duplicates, errors, elapsedMs);
    }

    private FilterResult applyFilters(RawAggregatorJob job) {
        FilterResult langResult = languageFilter.filter(job.title(), job.description());
        if (langResult.decision() == FilterDecision.SKIP) return langResult;

        FilterResult roleResult = roleRelevanceFilter.filter(job.title());
        if (roleResult.decision() == FilterDecision.SKIP) return roleResult;

        FilterResult locationResult = locationFilter.filter(job.location());
        if (locationResult.decision() == FilterDecision.SKIP) return locationResult;

        Integer yoe = yoeFilter.extractYoe(job.description());
        FilterResult yoeResult = yoeFilter.filter(yoe);
        if (yoeResult.decision() == FilterDecision.SKIP) return yoeResult;

        return FilterResult.keep();
    }

    private Company resolveCompany(String companyName, SourceConfig source) {
        String resolvedName = (companyName == null || companyName.isBlank()) ? "Unknown" : companyName;
        String normalized = normalizeCompanyName(resolvedName);
        return companyRepository.findByNormalizedName(normalized)
                .orElseGet(() -> {
                    Company newCompany = Company.builder()
                            .name(resolvedName)
                            .normalizedName(normalized)
                            .isActive(true)
                            .status(CompanyStatus.DISCOVERED)
                            .discoveredVia(source.discoverySource())
                            .discoveredAt(LocalDateTime.now())
                            .build();
                    return companyRepository.save(newCompany);
                });
    }

    private String normalizeCompanyName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void updateAggregatorRun(String sourceName, String status,
                                     int fetched, int created, int enriched, int filtered,
                                     int errors, long elapsedMs, String errorMessage) {
        AggregatorRun run = aggregatorRunRepository.findBySourceName(sourceName)
                .orElse(AggregatorRun.builder().sourceName(sourceName).build());
        run.setLastRunAt(LocalDateTime.now());
        run.setLastStatus(status);
        run.setJobsFetched(fetched);
        run.setJobsCreated(created);
        run.setJobsEnriched(enriched);
        run.setJobsFiltered(filtered);
        run.setErrors(errors);
        run.setElapsedMs(elapsedMs);
        run.setErrorMessage(errorMessage);
        aggregatorRunRepository.save(run);
    }
}
