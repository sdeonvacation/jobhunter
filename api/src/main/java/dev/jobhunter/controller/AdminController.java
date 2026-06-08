package dev.jobhunter.controller;

import dev.jobhunter.discovery.DiscoveryService;
import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.scheduler.PipelineScheduler;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.source.SourceConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CrawlService crawlService;
    private final CareerEndpointRepository careerEndpointRepository;
    private final ScoringScheduler scoringScheduler;
    private final DiscoveryService discoveryService;
    private final PipelineScheduler pipelineScheduler;
    private final AggregatorIngestionService aggregatorIngestionService;
    private final AggregatorRunRepository aggregatorRunRepository;
    private final List<SourceConfig> sources;

    public AdminController(CrawlService crawlService, CareerEndpointRepository careerEndpointRepository,
                           ScoringScheduler scoringScheduler, DiscoveryService discoveryService,
                           PipelineScheduler pipelineScheduler,
                           AggregatorIngestionService aggregatorIngestionService,
                           AggregatorRunRepository aggregatorRunRepository,
                           @Qualifier("allSources") List<SourceConfig> sources) {
        this.crawlService = crawlService;
        this.careerEndpointRepository = careerEndpointRepository;
        this.scoringScheduler = scoringScheduler;
        this.discoveryService = discoveryService;
        this.pipelineScheduler = pipelineScheduler;
        this.aggregatorIngestionService = aggregatorIngestionService;
        this.aggregatorRunRepository = aggregatorRunRepository;
        this.sources = sources;
    }

    @PostMapping("/pipeline")
    public ResponseEntity<String> triggerPipeline() {
        CompletableFuture.runAsync(pipelineScheduler::runPipeline);
        return ResponseEntity.accepted().body("Pipeline triggered");
    }

    @PostMapping("/crawl")
    public ResponseEntity<CrawlResult> triggerCrawl() {
        int[] stats = crawlService.crawlAllDueEndpoints();
        scoringScheduler.scoreAllUnscored();
        return ResponseEntity.ok(new CrawlResult(stats[0], stats[1], stats[2]));
    }

    @PostMapping("/crawl/aggregators")
    public ResponseEntity<List<IngestionStats>> triggerAggregatorCrawl() {
        List<IngestionStats> results = sources.stream()
                .map(aggregatorIngestionService::ingest)
                .toList();
        scoringScheduler.scoreAllUnscored();
        return ResponseEntity.ok(results);
    }

    @PostMapping("/crawl/{endpointId}")
    public ResponseEntity<SingleCrawlResult> crawlSingleEndpoint(@PathVariable UUID endpointId) {
        CareerEndpoint endpoint = careerEndpointRepository.findById(endpointId)
                .orElse(null);
        if (endpoint == null) {
            return ResponseEntity.notFound().build();
        }
        int jobsFound = crawlService.crawlEndpoint(endpoint);
        return ResponseEntity.ok(new SingleCrawlResult(endpointId, jobsFound));
    }

    @PostMapping("/backfill-descriptions")
    public ResponseEntity<BackfillResult> backfillDescriptions() {
        int filled = crawlService.backfillSmartRecruitersDescriptions();
        return ResponseEntity.ok(new BackfillResult(filled));
    }

    @PostMapping("/score")
    public ResponseEntity<String> triggerScoring() {
        scoringScheduler.scoreAllUnscored();
        return ResponseEntity.ok("Scoring complete");
    }

    @PostMapping("/discover")
    public ResponseEntity<DiscoverResult> triggerDiscovery() {
        int[] stats = discoveryService.runDiscovery();
        return ResponseEntity.ok(new DiscoverResult(stats[0], stats[1], stats[2]));
    }

    @PostMapping("/resolve")
    public ResponseEntity<ResolveResult> triggerResolve(@RequestParam(required = false) Integer limit) {
        int[] stats = discoveryService.resolveDiscoveredCompanies(limit);
        return ResponseEntity.ok(new ResolveResult(stats[0], stats[1], stats[2], stats[3]));
    }

    @PostMapping("/aggregate/{sourceName}")
    public ResponseEntity<?> triggerAggregation(@PathVariable String sourceName) {
        SourceConfig source = sources.stream()
                .filter(s -> s.name().equals(sourceName))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return ResponseEntity.notFound().build();
        }
        IngestionStats stats = aggregatorIngestionService.ingest(source);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/aggregators")
    public ResponseEntity<List<AggregatorStatus>> listAggregators() {
        List<AggregatorStatus> statuses = sources.stream()
                .map(source -> {
                    AggregatorRun lastRun = aggregatorRunRepository.findBySourceName(source.name())
                            .orElse(null);
                    return new AggregatorStatus(
                            source.name(),
                            source.sourceType().name(),
                            source.strategy().getClass().getSimpleName(),
                            source.frequencyHours(),
                            source.isEnabled(),
                            lastRun != null ? lastRun.getLastRunAt() : null,
                            lastRun != null ? lastRun.getLastStatus() : null,
                            lastRun != null ? lastRun.getJobsFetched() : 0,
                            lastRun != null ? lastRun.getJobsCreated() : 0,
                            lastRun != null ? lastRun.getErrors() : 0,
                            lastRun != null ? lastRun.getElapsedMs() : 0
                    );
                })
                .toList();
        return ResponseEntity.ok(statuses);
    }

    @GetMapping("/health")
    public ResponseEntity<HealthReport> getHealth() {
        List<CareerEndpoint> errorEndpoints = careerEndpointRepository
                .findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR);
        List<CareerEndpoint> emptyEndpoints = careerEndpointRepository
                .findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY);

        List<EndpointHealth> errors = errorEndpoints.stream()
                .map(e -> new EndpointHealth(
                        e.getId(),
                        e.getCompany() != null ? e.getCompany().getName() : "Unknown",
                        e.getAtsType().name(),
                        e.getAtsSlug(),
                        e.getUrl(),
                        e.getLastCrawlStatus().name(),
                        e.getLastErrorMessage(),
                        e.getConsecutiveErrors(),
                        e.getLastCrawledAt()
                )).toList();

        List<EndpointHealth> empties = emptyEndpoints.stream()
                .map(e -> new EndpointHealth(
                        e.getId(),
                        e.getCompany() != null ? e.getCompany().getName() : "Unknown",
                        e.getAtsType().name(),
                        e.getAtsSlug(),
                        e.getUrl(),
                        e.getLastCrawlStatus().name(),
                        null,
                        0,
                        e.getLastCrawledAt()
                )).toList();

        long totalActive = careerEndpointRepository.countByIsActiveTrue();
        long totalErrored = errors.size();
        long totalEmpty = empties.size();
        long neverCrawled = careerEndpointRepository.countByIsActiveTrueAndLastCrawlStatusIsNull();

        List<AggregatorHealth> aggregatorIssues = aggregatorRunRepository.findAll().stream()
                .filter(run -> "ERROR".equals(run.getLastStatus())
                        || "EMPTY".equals(run.getLastStatus())
                        || ("SUCCESS".equals(run.getLastStatus()) && run.getJobsFetched() == 0))
                .map(run -> new AggregatorHealth(
                        run.getSourceName(),
                        run.getLastStatus(),
                        run.getJobsFetched(),
                        run.getErrors(),
                        run.getErrorMessage(),
                        run.getLastRunAt(),
                        run.getElapsedMs()
                ))
                .toList();

        return ResponseEntity.ok(new HealthReport(totalActive, totalErrored, totalEmpty, neverCrawled, errors, empties, aggregatorIssues));
    }

    public record CrawlResult(int endpointsProcessed, int jobsFound, int errors) {}
    public record SingleCrawlResult(UUID endpointId, int jobsFound) {}
    public record BackfillResult(int descriptionsBackfilled) {}
    public record ResolveResult(int total, int resolved, int failed, int skipped) {}
    public record DiscoverResult(int providersQueried, int companiesFound, int newCompanies) {}

    public record AggregatorStatus(
            String name, String sourceType, String strategyName,
            int frequencyHours, boolean enabled,
            LocalDateTime lastRunAt, String lastStatus,
            int jobsFetched, int jobsCreated, int errors, long elapsedMs) {}

    public record EndpointHealth(
            UUID id, String companyName, String atsType, String atsSlug,
            String url, String status, String errorMessage,
            int consecutiveErrors, LocalDateTime lastCrawledAt) {}

    public record AggregatorHealth(
            String name, String status, int jobsFetched, int errors,
            String errorMessage, LocalDateTime lastRunAt, long elapsedMs) {}

    public record HealthReport(
            long totalEndpoints, long errored, long empty, long neverCrawled,
            List<EndpointHealth> errors, List<EndpointHealth> empties,
            List<AggregatorHealth> aggregatorIssues) {}
}
