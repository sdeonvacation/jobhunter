package dev.jobhunter.linkedin;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.OpportunityScore;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OpportunityScoreRepository;
import dev.jobhunter.repository.RecruiterPostCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automated recruiter-post detection batch runner. Dispatched asynchronously by
 * {@code PipelineScheduler} after each pipeline tick's final scoring pass.
 *
 * <p>Selects the top N not-yet-checked jobs from today's KEEP set (ordered by
 * OpportunityScore desc), runs slim checks within a shared daily call budget, and
 * never throws — failures are logged and retried on the next tick.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class RecruiterPostDetectionScheduler {

    private final RecruiterPostDetectionService recruiterPostDetectionService;
    private final RecruiterPostCheckRepository recruiterPostCheckRepository;
    private final JobPostingRepository jobPostingRepository;
    private final OpportunityScoreRepository opportunityScoreRepository;
    private final HttpMcpClient httpMcpClient;
    private final LinkedInMcpProperties mcpProperties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger dailyBudgetUsed = new AtomicInteger(0);
    private LocalDate budgetDate = LocalDate.now();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recruiter-post-detection");
        t.setDaemon(true);
        return t;
    });

    public RecruiterPostDetectionScheduler(RecruiterPostDetectionService recruiterPostDetectionService,
                                           RecruiterPostCheckRepository recruiterPostCheckRepository,
                                           JobPostingRepository jobPostingRepository,
                                           OpportunityScoreRepository opportunityScoreRepository,
                                           HttpMcpClient httpMcpClient,
                                           LinkedInMcpProperties mcpProperties) {
        this.recruiterPostDetectionService = recruiterPostDetectionService;
        this.recruiterPostCheckRepository = recruiterPostCheckRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.opportunityScoreRepository = opportunityScoreRepository;
        this.httpMcpClient = httpMcpClient;
        this.mcpProperties = mcpProperties;
    }

    /**
     * Fire-and-forget: submits {@link #runBatch()} to the single-thread executor.
     * Never blocks the caller.
     */
    public void runBatchAsync() {
        executor.submit(this::runBatch);
    }

    /**
     * Executes one batch run synchronously (also the executor target). Never throws.
     */
    public void runBatch() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Recruiter post detection already running, skipping");
            return;
        }
        try {
            runBatchInternal();
        } catch (Exception e) {
            log.error("Recruiter post detection batch failed unexpectedly", e);
        } finally {
            running.set(false);
        }
    }

    private void runBatchInternal() {
        LinkedInMcpProperties.RecruiterPostCheckConfig config = mcpProperties.recruiterPostCheck();
        LinkedInMcpProperties.AutomatedConfig auto = config.automated();

        if (!config.enabled() || !auto.enabled()) {
            log.info("Recruiter post detection automated mode disabled, skipping");
            return;
        }

        if (!httpMcpClient.isSessionValid()) {
            log.warn("LinkedIn MCP session invalid, skipping recruiter post detection (retry next tick)");
            return;
        }

        // Reset daily budget if the date rolled over.
        LocalDate today = LocalDate.now();
        if (!today.equals(budgetDate)) {
            budgetDate = today;
            dailyBudgetUsed.set(0);
        }

        List<JobPosting> jobs = selectTopNJobs(auto.topN());
        if (jobs.isEmpty()) {
            log.info("No eligible jobs for recruiter post detection, skipping");
            return;
        }

        int checked = 0;
        int skippedCached = 0;
        int skippedBudget = 0;
        int callsUsed = 0;
        int remaining = jobs.size();

        for (JobPosting job : jobs) {
            if (dailyBudgetUsed.get() >= auto.dailyCallBudget()) {
                skippedBudget += remaining;
                break;
            }
            remaining--;

            if (job.getApplyUrl() == null || job.getApplyUrl().isBlank()) {
                continue;
            }

            if (recruiterPostCheckRepository
                    .findByJobUrlAndExpiresAtAfter(job.getApplyUrl(), LocalDateTime.now())
                    .isPresent()) {
                skippedCached++;
                continue;
            }

            RecruiterPostDetectionService.RecruiterPostCheckResult result;
            try {
                result = recruiterPostDetectionService.checkRecruiterPost(job.getApplyUrl(), false);
            } catch (Exception e) {
                log.warn("Recruiter post check failed for job '{}' ({}): {}",
                        job.getTitle(), job.getApplyUrl(), e.getMessage());
                continue;
            }

            dailyBudgetUsed.addAndGet(result.callsUsed());
            checked++;
            callsUsed += result.callsUsed();
        }

        log.info("Recruiter post detection batch complete: checked={}, skippedCached={}, skippedBudget={}, callsUsed={}",
                checked, skippedCached, skippedBudget, callsUsed);
    }

    /**
     * Selects the top {@code limit} jobs from today's KEEP set, ordered by
     * OpportunityScore descending (stable). Returns an empty list when no
     * eligible jobs exist.
     */
    List<JobPosting> selectTopNJobs(int limit) {
        Page<JobPosting> page = jobPostingRepository
                .findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndDiscoveredDate(
                        FilterDecision.KEEP, LocalDate.now(), PageRequest.of(0, 500));

        return page.getContent().stream()
                .sorted(Comparator.comparingInt(this::scoreOf).reversed())
                .limit(limit)
                .toList();
    }

    private int scoreOf(JobPosting job) {
        return opportunityScoreRepository.findByJobId(job.getId())
                .map(OpportunityScore::getScore)
                .orElse(0);
    }

    public record BatchRunSummary(int checked, int skippedCached, int skippedBudget, int callsUsed) {}
}