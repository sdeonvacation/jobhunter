package dev.jobhunter.scheduler;

import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.service.ScoringService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Quartz job that triggers scoring. Delegates all logic to ScoringService.
 * Kept as thin wrapper so callers (AdminController, PipelineScheduler, ScoringPostProcessor)
 * need no changes.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class ScoringScheduler implements Job {

    private final ScoringService scoringService;

    public ScoringScheduler(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Scoring scheduler starting");
        scoringService.scoreAllUnscored();
    }

    public void scoreAllUnscored() {
        scoringService.scoreAllUnscored();
    }

    public void scoreJobsForEndpoint(UUID endpointId) {
        scoringService.scoreJobsForEndpoint(endpointId);
    }

    public void scoreJobsForSource(JobSource source) {
        scoringService.scoreJobsForSource(source);
    }
}
