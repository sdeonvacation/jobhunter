package dev.jobhunter.service;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OpportunityScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Generates daily digest snapshot with key metrics.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class DailyDigestService {

    private final JobPostingRepository jobPostingRepository;
    private final OpportunityScoreRepository opportunityScoreRepository;

    public DailyDigestService(JobPostingRepository jobPostingRepository,
                              OpportunityScoreRepository opportunityScoreRepository) {
        this.jobPostingRepository = jobPostingRepository;
        this.opportunityScoreRepository = opportunityScoreRepository;
    }

    /**
     * Compute daily digest metrics.
     */
    public DigestSnapshot computeDigest() {
        LocalDate today = LocalDate.now();

        // Count visible (KEEP) jobs discovered within the last 24 hours via a rolling window.
        // Keyed off createdAt (a timestamp), not discoveredDate (a calendar date), so jobs
        // discovered late in the day are not lost to a calendar-day boundary.
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        Page<JobPosting> recentJobs = jobPostingRepository
                .findDigestJobsSince(FilterDecision.KEEP, since, PageRequest.of(0, 500));
        int newJobsCount = (int) recentJobs.getTotalElements();

        // Find top opportunity (highest opportunity score among today's jobs)
        JobPosting topOpportunity = null;
        int topScore = 0;
        for (JobPosting job : recentJobs) {
            var oScore = opportunityScoreRepository.findByJobId(job.getId());
            if (oScore.isPresent() && oScore.get().getScore() > topScore) {
                topScore = oScore.get().getScore();
                topOpportunity = job;
            }
        }

        log.info("Daily digest computed: {} new jobs, top score: {}", newJobsCount, topScore);

        return new DigestSnapshot(
                today,
                newJobsCount,
                topOpportunity != null ? topOpportunity.getTitle() : null,
                topOpportunity != null && topOpportunity.getCompany() != null
                        ? topOpportunity.getCompany().getName() : null,
                topScore
        );
    }

    public record DigestSnapshot(
            LocalDate date,
            int newJobsCount,
            String topOpportunityTitle,
            String topOpportunityCompany,
            int topOpportunityScore
    ) {
    }
}
