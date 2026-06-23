package dev.jobhunter.scheduler;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
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
 * Periodic reaper (every 6h). Finds active jobs still in PENDING visa status
 * after 24h and deactivates them to prevent stale PENDING jobs leaking into the dashboard.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class VisaReaperScheduler implements Job {

    private static final String FILTER_REASON = "visa: pending timed out after 24h";

    private final JobPostingRepository jobPostingRepository;

    public VisaReaperScheduler(JobPostingRepository jobPostingRepository) {
        this.jobPostingRepository = jobPostingRepository;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Visa reaper starting");
        Instant start = Instant.now();

        try {
            int reaped = reapPendingVisaJobs();
            Duration elapsed = Duration.between(start, Instant.now());
            log.info("Visa reaper complete in {}ms: {} PENDING visa jobs deactivated", elapsed.toMillis(), reaped);
        } catch (Exception e) {
            log.error("Visa reaper failed", e);
        }
    }

    private int reapPendingVisaJobs() {
        // Cutoff = today: any job discovered before today is at least 24h old (LocalDate granularity)
        LocalDate cutoff = LocalDate.now();
        List<JobPosting> staleJobs = jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(cutoff);
        if (staleJobs.isEmpty()) {
            return 0;
        }

        for (JobPosting job : staleJobs) {
            job.setVisaSponsorship(VisaSponsorship.UNKNOWN);
            job.setActive(false);
            job.setFilterReason(FILTER_REASON);
        }
        jobPostingRepository.saveAll(staleJobs);

        log.info("Reaped {} PENDING visa jobs discovered before {}", staleJobs.size(), cutoff);
        return staleJobs.size();
    }
}
