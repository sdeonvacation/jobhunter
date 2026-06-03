package dev.jobhub.scheduler;

import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.service.MatchScoringService;
import dev.jobhub.service.OpportunityScoringService;
import dev.jobhub.service.SkillExtractionService;
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
 * Daily scoring job: extracts skills, computes match + opportunity scores for new jobs.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class ScoringScheduler implements Job {

    private static final int BATCH_SIZE = 50;

    private final JobPostingRepository jobPostingRepository;
    private final SkillExtractionService skillExtractionService;
    private final MatchScoringService matchScoringService;
    private final OpportunityScoringService opportunityScoringService;

    public ScoringScheduler(JobPostingRepository jobPostingRepository,
                            SkillExtractionService skillExtractionService,
                            MatchScoringService matchScoringService,
                            OpportunityScoringService opportunityScoringService) {
        this.jobPostingRepository = jobPostingRepository;
        this.skillExtractionService = skillExtractionService;
        this.matchScoringService = matchScoringService;
        this.opportunityScoringService = opportunityScoringService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Scoring scheduler starting");
        scoreAllUnscored();
    }

    /**
     * Score all unscored active jobs in batches. Can be called from scheduler or inline after crawl.
     */
    public void scoreAllUnscored() {
        Instant start = Instant.now();
        int totalExtracted = 0;
        int totalMatched = 0;
        int totalOpportunities = 0;

        try {
            Page<JobPosting> page;
            do {
                // Always fetch page 0: scored jobs drop out of the query each iteration
                page = jobPostingRepository.findUnscoredActiveJobs(
                        FilterDecision.KEEP, PageRequest.of(0, BATCH_SIZE));
                List<JobPosting> jobs = page.getContent();
                if (jobs.isEmpty()) {
                    break;
                }

                totalExtracted += skillExtractionService.extractSkillsBatch(jobs);
                totalMatched += matchScoringService.scoreJobs(jobs);
                totalOpportunities += opportunityScoringService.scoreJobs(jobs);
            } while (page.hasNext());

            Duration elapsed = Duration.between(start, Instant.now());
            log.info("Scoring complete in {}s: extracted={}, matched={}, opportunities={}",
                    elapsed.toSeconds(), totalExtracted, totalMatched, totalOpportunities);
        } catch (Exception e) {
            log.error("Scoring scheduler failed", e);
        }
    }
}
