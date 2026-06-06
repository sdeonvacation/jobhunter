package dev.jobhub.scheduler;

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

/**
 * Unified pipeline scheduler: crawl → linkedin search → scoring (match + opportunity).
 * Runs as a single sequential job so UI always has complete data.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class PipelineScheduler implements Job {

    private final CrawlService crawlService;
    private final ScoringScheduler scoringScheduler;

    @Autowired(required = false)
    private LinkedInJobSearchService linkedInJobSearchService;

    public PipelineScheduler(CrawlService crawlService, ScoringScheduler scoringScheduler) {
        this.crawlService = crawlService;
        this.scoringScheduler = scoringScheduler;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Pipeline starting: crawl → linkedin → scoring");
        Instant start = Instant.now();

        // Step 1: Crawl all ATS endpoints
        try {
            log.info("[Pipeline 1/3] Crawling ATS endpoints");
            int[] crawlStats = crawlService.crawlAllDueEndpoints();
            log.info("[Pipeline 1/3] Crawl complete: endpoints={}, jobs={}, errors={}",
                    crawlStats[0], crawlStats[1], crawlStats[2]);
        } catch (Exception e) {
            log.error("[Pipeline 1/3] Crawl failed, continuing pipeline", e);
        }

        // Step 2: LinkedIn job search + matching
        if (linkedInJobSearchService != null) {
            try {
                log.info("[Pipeline 2/3] LinkedIn job search");
                int[] linkedInStats = linkedInJobSearchService.searchAndMatch();
                log.info("[Pipeline 2/3] LinkedIn complete: enriched={}, created={}, searches={}",
                        linkedInStats[0], linkedInStats[1], linkedInStats[2]);
            } catch (Exception e) {
                log.error("[Pipeline 2/3] LinkedIn search failed, continuing pipeline", e);
            }
        } else {
            log.info("[Pipeline 2/3] LinkedIn search skipped (not enabled)");
        }

        // Step 3: Score all unscored jobs (match + opportunity)
        try {
            log.info("[Pipeline 3/3] Scoring unscored jobs");
            scoringScheduler.scoreAllUnscored();
            log.info("[Pipeline 3/3] Scoring complete");
        } catch (Exception e) {
            log.error("[Pipeline 3/3] Scoring failed", e);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Pipeline complete in {}s", elapsed.toSeconds());
    }
}
