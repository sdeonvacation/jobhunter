package dev.jobhunter.scheduler;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.service.MatchScoringService;
import dev.jobhunter.service.OpportunityScoringService;
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
import java.util.UUID;

import dev.jobhunter.model.enums.JobSource;

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

    /**
     * Score unscored KEEP jobs for a specific aggregator source. Called immediately after each source ingestion.
     */
    public void scoreJobsForSource(JobSource source) {
        List<JobPosting> jobs = jobPostingRepository.findUnscoredActiveJobsBySource(
                source, FilterDecision.KEEP);
        if (jobs.isEmpty()) return;

        int matched = matchScoringService.scoreJobs(jobs);
        int opportunities = opportunityScoringService.scoreJobs(jobs);
        log.debug("Scored source [{}]: {} jobs, matched={}, opportunities={}",
                source, jobs.size(), matched, opportunities);
    }

    /**
     * Score unscored KEEP jobs for a specific endpoint. Called immediately after each endpoint crawl.
     */
    public void scoreJobsForEndpoint(UUID endpointId) {
        List<JobPosting> jobs = jobPostingRepository.findUnscoredActiveJobsByEndpoint(
                endpointId, FilterDecision.KEEP);
        if (jobs.isEmpty()) return;

        int matched = matchScoringService.scoreJobs(jobs);
        int opportunities = opportunityScoringService.scoreJobs(jobs);
        log.debug("Scored endpoint [{}]: {} jobs, matched={}, opportunities={}",
                endpointId, jobs.size(), matched, opportunities);
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
