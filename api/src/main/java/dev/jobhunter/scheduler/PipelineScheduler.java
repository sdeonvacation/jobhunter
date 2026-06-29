package dev.jobhunter.scheduler;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.service.ScoringService;
import dev.jobhunter.source.SourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified pipeline scheduler: [crawl + aggregator sources] in parallel → scoring.
 * All active ATS endpoints and enabled aggregator sources run every pipeline execution.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class PipelineScheduler implements Job {

    private final CrawlService crawlService;
    private final ScoringService scoringService;
    private final AggregatorIngestionService aggregatorIngestionService;
    private final List<SourceConfig> sources;
    private final ExecutorService sourceExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PipelineScheduler(CrawlService crawlService,
                             ScoringService scoringService,
                             AggregatorIngestionService aggregatorIngestionService,
                             @Qualifier("allSources") List<SourceConfig> sources,
                             @Value("${aggregator.pipeline.max-concurrent-sources:3}") int maxConcurrentSources) {
        this.crawlService = crawlService;
        this.scoringService = scoringService;
        this.aggregatorIngestionService = aggregatorIngestionService;
        this.sources = sources;
        this.sourceExecutor = Executors.newFixedThreadPool(maxConcurrentSources);
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        runPipeline();
    }

    /**
     * Run the full pipeline. Can be called from Quartz or manually via admin endpoint.
     * Guards against concurrent execution — only one pipeline run at a time.
     */
    public void runPipeline() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Pipeline already running, skipping concurrent trigger");
            return;
        }
        try {
            runPipelineInternal();
        } finally {
            running.set(false);
        }
    }

    private void runPipelineInternal() {
        log.info("Pipeline starting: [crawl + {} aggregator sources] parallel → scoring", sources.size());
        Instant start = Instant.now();

        // Step 1: Crawl ATS endpoints (crawlAllDueEndpoints() is itself parallelized internally)
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
        });

        // Step 2: Run each enabled aggregator source through a bounded executor
        // to avoid saturating the network with too many concurrent HTTP connections.
        List<CompletableFuture<IngestionStats>> sourceFutures = sources.stream()
                .filter(SourceConfig::isEnabled)
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
                }, sourceExecutor).whenComplete((stats, err) -> {
                    if (err == null && stats != null && stats.created() > 0) {
                        try {
                            log.info("[Pipeline] Scoring {} new jobs from source {}", stats.created(), stats.sourceName());
                            scoringService.scoreJobsForSource(source.sourceType());
                        } catch (Exception e) {
                            log.error("[Pipeline] Post-source scoring failed for {}", stats.sourceName(), e);
                        }
                    }
                }))
                .toList();

        // Wait for crawl + all sources
        crawlFuture.join();
        sourceFutures.forEach(CompletableFuture::join);

        // Step 3: Final scoring pass — catches any jobs created concurrently during the pipeline
        try {
            log.info("[Pipeline] Final scoring pass");
            scoringService.scoreAllUnscored();
            log.info("[Pipeline] Scoring complete");
        } catch (Exception e) {
            log.error("[Pipeline] Scoring failed", e);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Pipeline complete in {}s", elapsed.toSeconds());
    }

}
