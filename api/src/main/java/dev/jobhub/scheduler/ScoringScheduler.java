package dev.jobhub.scheduler;

import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.service.MatchScoringService;
import dev.jobhub.service.OpportunityScoringService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scores unscored jobs using keyword matching (no AI). Runs on startup + daily.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class ScoringScheduler implements Job {

    private static final int BATCH_SIZE = 200;

    private final JobPostingRepository jobPostingRepository;
    private final MatchScoringService matchScoringService;
    private final OpportunityScoringService opportunityScoringService;

    public ScoringScheduler(JobPostingRepository jobPostingRepository,
                            MatchScoringService matchScoringService,
                            OpportunityScoringService opportunityScoringService) {
        this.jobPostingRepository = jobPostingRepository;
        this.matchScoringService = matchScoringService;
        this.opportunityScoringService = opportunityScoringService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Scoring scheduler starting");
        scoreAllUnscored();
    }

    public void scoreAllUnscored() {
        Instant start = Instant.now();
        int totalMatched = 0;
        int totalOpportunities = 0;

        try {
            Page<JobPosting> page;
            do {
                page = jobPostingRepository.findUnscoredActiveJobs(
                        FilterDecision.KEEP, PageRequest.of(0, BATCH_SIZE));
                List<JobPosting> jobs = page.getContent();
                if (jobs.isEmpty()) {
                    break;
                }

                totalMatched += matchScoringService.scoreJobs(jobs);
                totalOpportunities += opportunityScoringService.scoreJobs(jobs);
            } while (page.hasNext());

            Duration elapsed = Duration.between(start, Instant.now());
            log.info("Scoring complete in {}s: matched={}, opportunities={}",
                    elapsed.toSeconds(), totalMatched, totalOpportunities);
        } catch (Exception e) {
            log.error("Scoring scheduler failed", e);
        }
    }
}
