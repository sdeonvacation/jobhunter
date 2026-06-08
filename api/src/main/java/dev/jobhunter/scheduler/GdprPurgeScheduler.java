package dev.jobhunter.scheduler;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.JobPostingRepository;
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

/**
 * Nightly purge job (2:00 AM). Removes expired recruiter PII and old unapplied jobs.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class GdprPurgeScheduler implements Job {

    private static final int JOB_RETENTION_DAYS = 30;

    private final RecruiterDataService recruiterDataService;
    private final JobPostingRepository jobPostingRepository;

    public GdprPurgeScheduler(RecruiterDataService recruiterDataService,
                              JobPostingRepository jobPostingRepository) {
        this.recruiterDataService = recruiterDataService;
        this.jobPostingRepository = jobPostingRepository;
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

    private int purgeOldJobs() {
        LocalDate cutoff = LocalDate.now().minusDays(JOB_RETENTION_DAYS);
        List<JobPosting> staleJobs = jobPostingRepository.findByDiscoveredDateBeforeAndAppliedFalse(cutoff);
        if (staleJobs.isEmpty()) {
            return 0;
        }
        jobPostingRepository.deleteAll(staleJobs);
        log.info("Purged {} unapplied jobs older than {} days (before {})",
                staleJobs.size(), JOB_RETENTION_DAYS, cutoff);
        return staleJobs.size();
    }
}
