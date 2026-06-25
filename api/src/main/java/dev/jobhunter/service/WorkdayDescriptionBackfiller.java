package dev.jobhunter.service;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.ingestion.DescriptionBackfiller;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import dev.jobhunter.strategy.ats.WorkdayStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Backfills descriptions for Workday KEEP jobs whose description is a short bulletFields
 * stub (the list API only returns metadata tags, not the actual job description).
 * Calls the Workday CXS detail API per job, then re-runs DescriptionFilterChain.
 */
@Slf4j
@Service
public class WorkdayDescriptionBackfiller implements DescriptionBackfiller {

    /** Jobs with description shorter than this are considered stubs needing backfill. */
    private static final int MAX_STUB_LENGTH = 500;
    private static final long DELAY_MS = 300;

    private final JobPostingRepository jobPostingRepository;
    private final WorkdayStrategy workdayStrategy;
    private final DescriptionFilterChain descriptionFilterChain;
    private final MatchScoreRepository matchScoreRepository;

    public WorkdayDescriptionBackfiller(JobPostingRepository jobPostingRepository,
                                        WorkdayStrategy workdayStrategy,
                                        DescriptionFilterChain descriptionFilterChain,
                                        MatchScoreRepository matchScoreRepository) {
        this.jobPostingRepository = jobPostingRepository;
        this.workdayStrategy = workdayStrategy;
        this.descriptionFilterChain = descriptionFilterChain;
        this.matchScoreRepository = matchScoreRepository;
    }

    @Override
    @Transactional
    public void backfill() {
        List<JobPosting> jobs = jobPostingRepository
                .findBySourceAndLanguageFilterAndShortDescription(JobSource.WORKDAY, FilterDecision.KEEP, MAX_STUB_LENGTH);

        if (jobs.isEmpty()) return;

        log.info("Workday backfill: {} KEEP jobs with stub descriptions", jobs.size());
        int filled = 0;

        for (JobPosting job : jobs) {
            if (job.getEndpoint() == null) {
                log.debug("Workday backfill: skipping {} — no endpoint", job.getId());
                continue;
            }

            String description = workdayStrategy.fetchDescription(job.getEndpoint(), job.getExternalId());
            if (description != null && !description.isBlank()) {
                job.setDescription(description);
                descriptionFilterChain.refilter(job);
                jobPostingRepository.save(job);
                matchScoreRepository.deleteByJobId(job.getId());
                filled++;
                log.debug("Workday backfill: filled description for {} ({})", job.getId(), job.getTitle());

                try {
                    Thread.sleep(DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Workday backfill interrupted after {}/{}", filled, jobs.size());
                    return;
                }
            }
        }

        log.info("Workday backfill: {}/{} descriptions filled", filled, jobs.size());
    }
}
