package dev.jobhunter.scheduler;

import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.service.RecruiterDataService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Nightly purge job (2:00 AM). Removes expired recruiter PII and old unapplied jobs.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class GdprPurgeScheduler implements Job {

    private static final int JOB_RETENTION_DAYS = 30;
    private static final int PURGE_BATCH_SIZE = 2000;

    private final RecruiterDataService recruiterDataService;
    private final JobPostingRepository jobPostingRepository;
    private final OutreachContactRepository outreachContactRepository;

    public GdprPurgeScheduler(RecruiterDataService recruiterDataService,
                              JobPostingRepository jobPostingRepository,
                              OutreachContactRepository outreachContactRepository) {
        this.recruiterDataService = recruiterDataService;
        this.jobPostingRepository = jobPostingRepository;
        this.outreachContactRepository = outreachContactRepository;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Nightly purge starting");
        Instant start = Instant.now();

        try {
            int recruitersPurged = recruiterDataService.purgeExpiredData();
            int jobsPurged = purgeOldJobs();
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("Nightly purge complete in {}ms: {} recruiter records, {} old jobs purged",
                    elapsed.toMillis(), recruitersPurged, jobsPurged);
        } catch (Exception e) {
            log.error("Nightly purge failed", e);
        }
    }

    /**
     * Deletes stale unapplied jobs in bounded batches of ids. A job is only purged when it is BOTH older
     * than {@link #JOB_RETENTION_DAYS} (by discovery) AND has not been seen (by {@code last_crawled_at})
     * within that window, so jobs still listed on a source are retained instead of being re-imported.
     * Entities are never loaded, so the purge cannot materialise the multi-hundred-MB stale set into the
     * JVM heap.
     */
    private int purgeOldJobs() {
        LocalDate cutoff = LocalDate.now().minusDays(JOB_RETENTION_DAYS);
        int totalPurged = 0;

        while (true) {
            List<UUID> ids = jobPostingRepository.findPurgeableJobIds(cutoff, PURGE_BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            // job_contact is a NO ACTION FK link table: clear the links before deleting the job rows.
            outreachContactRepository.deleteJobContactsByJobIds(ids);
            totalPurged += jobPostingRepository.deleteByIds(ids);
            if (ids.size() < PURGE_BATCH_SIZE) {
                break;
            }
        }

        if (totalPurged > 0) {
            log.info("Purged {} unapplied jobs older than {} days (before {})",
                    totalPurged, JOB_RETENTION_DAYS, cutoff);
        }
        return totalPurged;
    }
}
