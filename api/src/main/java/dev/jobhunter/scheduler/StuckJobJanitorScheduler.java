package dev.jobhunter.scheduler;

import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Daily janitor (04:00). Bounds the {@code url-dead-stuck-*} tombstone table
 * by soft-hiding rows that have been stuck for more than 30 days. Soft-hide
 * only (preserves the audit trail); hard-delete is a future enhancement.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class StuckJobJanitorScheduler implements Job {

    private static final Duration SOFT_HIDE_AGE = Duration.ofDays(30);
    private static final String FILTER_REASON_PREFIX = "url-dead-stuck-%";

    private final JobPostingRepository jobPostingRepository;

    public StuckJobJanitorScheduler(JobPostingRepository jobPostingRepository) {
        this.jobPostingRepository = jobPostingRepository;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Stuck job janitor starting");
        Instant start = Instant.now();

        try {
            int hidden = reapStuckJobs();
            Duration elapsed = Duration.between(start, Instant.now());
            log.info("Stuck job janitor complete in {}ms: {} url-dead-stuck tombstones soft-hidden (cutoff={})",
                    elapsed.toMillis(), hidden, LocalDateTime.now().minus(SOFT_HIDE_AGE));
        } catch (Exception e) {
            log.error("Stuck job janitor failed", e);
        }
    }

    private int reapStuckJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minus(SOFT_HIDE_AGE);

        try {
            int hidden = jobPostingRepository.softHideStuckJobsOlderThan(FILTER_REASON_PREFIX, cutoff);
            log.info("Soft-hid {} stuck jobs with filter_reason like '{}' updated before {}",
                    hidden, FILTER_REASON_PREFIX, cutoff);
            return hidden;
        } catch (Exception e) {
            log.error("Soft-hide of stuck jobs failed for cutoff={}", cutoff, e);
            return 0;
        }
    }
}
