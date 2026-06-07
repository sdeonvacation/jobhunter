package dev.jobhunter.scheduler;

import dev.jobhunter.service.DailyDigestService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Daily digest generation job (7:30 AM).
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class DigestScheduler implements Job {

    private final DailyDigestService dailyDigestService;

    public DigestScheduler(DailyDigestService dailyDigestService) {
        this.dailyDigestService = dailyDigestService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Daily digest generation starting");
        Instant start = Instant.now();

        try {
            DailyDigestService.DigestSnapshot digest = dailyDigestService.computeDigest();
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("Daily digest generated in {}ms: {} new jobs, top score: {}",
                    elapsed.toMillis(), digest.newJobsCount(), digest.topOpportunityScore());
        } catch (Exception e) {
            log.error("Daily digest generation failed", e);
        }
    }
}
