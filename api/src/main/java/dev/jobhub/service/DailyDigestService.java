package dev.jobhub.service;

import dev.jobhub.model.JobPosting;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.repository.OpportunityScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

        // Get active jobs and filter by today's discovery date
        Page<JobPosting> activeJobs = jobPostingRepository.findByIsActiveTrue(PageRequest.of(0, 500));
        List<JobPosting> recentJobs = activeJobs.getContent().stream()
                .filter(j -> today.equals(j.getDiscoveredDate()))
                .toList();
        int newJobsCount = recentJobs.size();

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
