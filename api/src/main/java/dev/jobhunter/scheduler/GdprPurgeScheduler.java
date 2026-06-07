package dev.jobhunter.scheduler;

import dev.jobhunter.service.RecruiterDataService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Nightly GDPR purge job (2:00 AM). Removes expired recruiter PII.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class GdprPurgeScheduler implements Job {

    private final RecruiterDataService recruiterDataService;

    public GdprPurgeScheduler(RecruiterDataService recruiterDataService) {
        this.recruiterDataService = recruiterDataService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("GDPR purge starting");
        Instant start = Instant.now();

        try {
            int purged = recruiterDataService.purgeExpiredData();
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("GDPR purge complete in {}ms: {} records purged",
                    elapsed.toMillis(), purged);
        } catch (Exception e) {
            log.error("GDPR purge failed", e);
        }
    }
}
