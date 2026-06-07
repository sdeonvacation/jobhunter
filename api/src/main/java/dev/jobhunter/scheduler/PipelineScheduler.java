package dev.jobhunter.scheduler;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.source.SourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified pipeline scheduler: [crawl + aggregator sources] in parallel → scoring.
 * Crawl and all enabled/due aggregator sources run concurrently, scoring waits for all to complete.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class PipelineScheduler implements Job {

    private static final ExecutorService PIPELINE_POOL = Executors.newFixedThreadPool(3);

    private final CrawlService crawlService;
    private final ScoringScheduler scoringScheduler;
    private final AggregatorIngestionService aggregatorIngestionService;
    private final AggregatorRunRepository aggregatorRunRepository;
    private final List<SourceConfig> sources;

    public PipelineScheduler(CrawlService crawlService,
                             ScoringScheduler scoringScheduler,
                             AggregatorIngestionService aggregatorIngestionService,
                             AggregatorRunRepository aggregatorRunRepository,
                             @Qualifier("allSources") List<SourceConfig> sources) {
        this.crawlService = crawlService;
        this.scoringScheduler = scoringScheduler;
        this.aggregatorIngestionService = aggregatorIngestionService;
        this.aggregatorRunRepository = aggregatorRunRepository;
        this.sources = sources;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        runPipeline();
    }

    /**
     * Run the full pipeline. Can be called from Quartz or manually via admin endpoint.
     */
    public void runPipeline() {
        log.info("Pipeline starting: [crawl + {} aggregator sources] parallel → scoring", sources.size());
        Instant start = Instant.now();

        // Step 1: Crawl ATS endpoints
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

        // Step 2: Run each enabled and due aggregator source in parallel
        List<CompletableFuture<IngestionStats>> sourceFutures = sources.stream()
                .filter(SourceConfig::isEnabled)
                .filter(this::isDue)
                .map(source -> CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("[Pipeline] Ingesting source: {}", source.name());
                        IngestionStats stats = aggregatorIngestionService.ingest(source);
                        log.info("[Pipeline] Source {} complete: fetched={}, created={}, filtered={}, errors={}",
                                stats.sourceName(), stats.fetched(), stats.created(), stats.filtered(), stats.errors());
                        return stats;
                    } catch (Exception e) {
                        log.error("[Pipeline] Source {} failed", source.name(), e);
                        return new IngestionStats(source.name(), 0, 0, 0, 0, 0, 1, 0);
                    }
                }, PIPELINE_POOL))
                .toList();

        // Wait for crawl + all sources
        crawlFuture.join();
        sourceFutures.forEach(CompletableFuture::join);

        // Step 3: Score all unscored jobs
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

    /**
     * Check if a source is due for execution based on its frequencyHours and last run time.
     */
    boolean isDue(SourceConfig source) {
        return aggregatorRunRepository.findBySourceName(source.name())
                .map(run -> {
                    LocalDateTime nextDue = run.getLastRunAt().plusHours(source.frequencyHours());
                    boolean due = LocalDateTime.now().isAfter(nextDue);
                    if (!due) {
                        log.debug("[Pipeline] Source {} not due until {}", source.name(), nextDue);
                    }
                    return due;
                })
                .orElse(true); // Never run before → due
    }
}
