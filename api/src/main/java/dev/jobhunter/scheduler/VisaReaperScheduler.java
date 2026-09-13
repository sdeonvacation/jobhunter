package dev.jobhunter.scheduler;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Periodic reaper (every 6h). Finds active jobs still in PENDING visa status
 * after 24h and either resolves or deactivates them, so stale PENDING jobs do
 * not leak into the dashboard.
 *
 * <p>Rows that already carry a usable description are re-evaluated through
 * {@link DescriptionFilterChain#refilter} - which always resolves PENDING when it
 * runs - instead of being discarded. Only jobs that still cannot be decided are
 * deactivated.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class VisaReaperScheduler implements Job {

    private static final String FILTER_REASON = "visa: pending timed out after 24h";

    private final JobPostingRepository jobPostingRepository;
    private final DescriptionFilterChain descriptionFilterChain;
    private final int minDescriptionLength;

    public VisaReaperScheduler(JobPostingRepository jobPostingRepository,
                               DescriptionFilterChain descriptionFilterChain,
                               @Value("${aggregator.enrichment.min-description-length:500}") int minDescriptionLength) {
        this.jobPostingRepository = jobPostingRepository;
        this.descriptionFilterChain = descriptionFilterChain;
        this.minDescriptionLength = minDescriptionLength;
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
        List<JobPosting> candidates = jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(cutoff);
        if (candidates.isEmpty()) {
            return 0;
        }

        List<JobPosting> toSave = new ArrayList<>();
        int reaped = 0;
        int resolved = 0;

        for (JobPosting job : candidates) {
            // A job that already passed language and YOE and has real text can still be
            // decided - do that rather than discarding it. refilter() never leaves PENDING
            // set, so a residual PENDING means there was nothing usable to decide from.
            if (hasDecidableDescription(job)) {
                descriptionFilterChain.refilter(job);
                if (job.getVisaSponsorship() != VisaSponsorship.PENDING) {
                    toSave.add(job);
                    resolved++;
                    continue;
                }
            }

            job.setVisaSponsorship(VisaSponsorship.UNKNOWN);
            job.setActive(false);
            // Never overwrite an existing rejection reason: doing so hides the real cause
            // (e.g. "non-English JD (German)") behind a misleading visa-timeout label.
            if (job.getFilterReason() == null || job.getFilterReason().isBlank()) {
                job.setFilterReason(FILTER_REASON);
            }
            toSave.add(job);
            reaped++;
        }

        jobPostingRepository.saveAll(toSave);
        log.info("Visa reaper: {} of {} candidates discovered before {} resolved, {} deactivated",
                resolved, candidates.size(), cutoff, reaped);
        return reaped;
    }

    private boolean hasDecidableDescription(JobPosting job) {
        String description = job.getDescription();
        return description != null && description.length() >= minDescriptionLength;
    }
}
