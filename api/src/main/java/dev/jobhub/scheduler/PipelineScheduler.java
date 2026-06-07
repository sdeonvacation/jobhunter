package dev.jobhub.scheduler;

import dev.jobhub.indeed.IndeedJobSearchService;
import dev.jobhub.linkedin.LinkedInJobSearchService;
import dev.jobhub.service.CrawlService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified pipeline scheduler: [crawl + linkedin + indeed] in parallel → scoring.
 * Steps 1-3 run concurrently for speed, scoring waits for all to complete.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class PipelineScheduler implements Job {

    private static final ExecutorService PIPELINE_POOL = Executors.newFixedThreadPool(3);

    private final CrawlService crawlService;
    private final ScoringScheduler scoringScheduler;

    @Autowired(required = false)
    private LinkedInJobSearchService linkedInJobSearchService;

    @Autowired(required = false)
    private IndeedJobSearchService indeedJobSearchService;

    public PipelineScheduler(CrawlService crawlService, ScoringScheduler scoringScheduler) {
        this.crawlService = crawlService;
        this.scoringScheduler = scoringScheduler;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        runPipeline();
    }

    /**
     * Run the full pipeline. Can be called from Quartz or manually via admin endpoint.
     */
    public void runPipeline() {
        log.info("Pipeline starting: [crawl + linkedin + indeed] parallel → scoring");
        Instant start = Instant.now();

        // Steps 1-3: Run crawl, LinkedIn, and Indeed in parallel
        CompletableFuture<int[]> crawlFuture = CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[Pipeline] Crawling ATS endpoints");
                int[] stats = crawlService.crawlAllDueEndpoints();
                log.info("[Pipeline] Crawl complete: endpoints={}, jobs={}, errors={}",
                        stats[0], stats[1], stats[2]);
                return stats;
            } catch (Exception e) {
                log.error("[Pipeline] Crawl failed", e);
                return new int[]{0, 0, 0};
            }
        }, PIPELINE_POOL);

        CompletableFuture<int[]> linkedInFuture = CompletableFuture.supplyAsync(() -> {
            if (linkedInJobSearchService == null) {
                log.info("[Pipeline] LinkedIn search skipped (not enabled)");
                return new int[]{0, 0, 0};
            }
            try {
                log.info("[Pipeline] LinkedIn job search");
                int[] stats = linkedInJobSearchService.searchAndMatch();
                log.info("[Pipeline] LinkedIn complete: enriched={}, created={}, searches={}",
                        stats[0], stats[1], stats[2]);
                return stats;
            } catch (Exception e) {
                log.error("[Pipeline] LinkedIn search failed", e);
                return new int[]{0, 0, 0};
            }
        }, PIPELINE_POOL);

        CompletableFuture<int[]> indeedFuture = CompletableFuture.supplyAsync(() -> {
            if (indeedJobSearchService == null) {
                log.info("[Pipeline] Indeed search skipped (not enabled)");
                return new int[]{0, 0, 0};
            }
            try {
                log.info("[Pipeline] Indeed job search");
                int[] stats = indeedJobSearchService.searchAndCreate();
                log.info("[Pipeline] Indeed complete: created={}, filtered={}, searches={}",
                        stats[0], stats[1], stats[2]);
                return stats;
            } catch (Exception e) {
                log.error("[Pipeline] Indeed search failed", e);
                return new int[]{0, 0, 0};
            }
        }, PIPELINE_POOL);

        // Wait for all three to complete before scoring
        CompletableFuture.allOf(crawlFuture, linkedInFuture, indeedFuture).join();

        // Step 4: Score all unscored jobs
        try {
            log.info("[Pipeline] Scoring unscored jobs");
            scoringScheduler.scoreAllUnscored();
            log.info("[Pipeline] Scoring complete");
        } catch (Exception e) {
            log.error("[Pipeline] Scoring failed", e);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Pipeline complete in {}s", elapsed.toSeconds());
    }
}
