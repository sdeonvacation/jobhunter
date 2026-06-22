package dev.jobhunter.controller;

import dev.jobhunter.discovery.DiscoveryService;
import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.linkedin.HttpMcpClient;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import dev.jobhunter.repository.OpportunityScoreRepository;
import dev.jobhunter.scheduler.PipelineScheduler;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.source.SourceConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final MatchScoreRepository matchScoreRepository;
    private final OpportunityScoreRepository opportunityScoreRepository;
    private final JobPostingRepository jobPostingRepository;
    private final Optional<HttpMcpClient> httpMcpClient;
    private final List<SourceConfig> sources;

    public AdminController(CrawlService crawlService, CareerEndpointRepository careerEndpointRepository,
                           ScoringScheduler scoringScheduler, DiscoveryService discoveryService,
                           PipelineScheduler pipelineScheduler,
                           AggregatorIngestionService aggregatorIngestionService,
                           AggregatorRunRepository aggregatorRunRepository,
                           MatchScoreRepository matchScoreRepository,
                           OpportunityScoreRepository opportunityScoreRepository,
                           JobPostingRepository jobPostingRepository,
                           Optional<HttpMcpClient> httpMcpClient,
                           @Qualifier("allSources") List<SourceConfig> sources) {
        this.crawlService = crawlService;
        this.careerEndpointRepository = careerEndpointRepository;
        this.scoringScheduler = scoringScheduler;
        this.discoveryService = discoveryService;
        this.pipelineScheduler = pipelineScheduler;
        this.aggregatorIngestionService = aggregatorIngestionService;
        this.aggregatorRunRepository = aggregatorRunRepository;
        this.matchScoreRepository = matchScoreRepository;
        this.opportunityScoreRepository = opportunityScoreRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.httpMcpClient = httpMcpClient;
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
        int[] result = crawlService.backfillSmartRecruitersDescriptions();
        return ResponseEntity.ok(new BackfillResult(result[0], result[1]));
    }

    @PostMapping("/score")
    public ResponseEntity<String> triggerScoring() {
        scoringScheduler.scoreAllUnscored();
        return ResponseEntity.ok("Scoring complete");
    }

    @PostMapping("/rescore")
    @Transactional
    public ResponseEntity<RescoreResult> rescoreAll() {
        long deleted = matchScoreRepository.count();
        matchScoreRepository.deleteAllInBatch();
        opportunityScoreRepository.deleteAllInBatch();
        scoringScheduler.scoreAllUnscored();
        long rescored = matchScoreRepository.count();
        return ResponseEntity.ok(new RescoreResult(deleted, rescored));
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

    private static final Pattern RELATIVE_TIME_PATTERN =
            Pattern.compile("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago");

    @PostMapping("/backfill-linkedin-dates")
    public ResponseEntity<Map<String, Integer>> backfillLinkedInDates() {
        if (httpMcpClient.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", -1, "updated", 0, "skipped", 0));
        }
        HttpMcpClient mcp = httpMcpClient.get();

        List<JobPosting> jobs = jobPostingRepository
                .findBySourceAndLanguageFilterAndPostedDateIsNull(JobSource.LINKEDIN, FilterDecision.KEEP);

        int updated = 0, skipped = 0, errors = 0;
        for (JobPosting job : jobs) {
            try {
                var response = mcp.callTool("get_job_details", Map.of("job_id", job.getExternalId()));
                LocalDate date = extractPostedDateFromMcpResponse(response);
                if (date != null) {
                    job.setPostedDate(date);
                    jobPostingRepository.save(job);
                    updated++;
                } else {
                    skipped++;
                }
                Thread.sleep(2000); // rate limit
            } catch (Exception e) {
                errors++;
                if (errors > 5) break; // circuit breaker
            }
        }
        return ResponseEntity.ok(Map.of("updated", updated, "skipped", skipped, "errors", errors, "total", jobs.size()));
    }

    private LocalDate extractPostedDateFromMcpResponse(com.fasterxml.jackson.databind.JsonNode response) {
        if (response == null) return null;
        // Search all text content for relative time patterns
        String text = response.toString();
        // Look for "posted X ago" or "reposted X ago" or just "X days/weeks ago" in the response
        Matcher m = RELATIVE_TIME_PATTERN.matcher(text);
        if (!m.find()) return null;

        int amount = Integer.parseInt(m.group(1));
        String unit = m.group(2);
        LocalDate today = LocalDate.now();
        return switch (unit) {
            case "second", "minute", "hour" -> today;
            case "day" -> today.minusDays(amount);
            case "week" -> today.minusWeeks(amount);
            case "month" -> today.minusMonths(amount);
            case "year" -> today.minusYears(amount);
            default -> null;
        };
    }

    public record CrawlResult(int endpointsProcessed, int jobsFound, int errors) {}
    public record SingleCrawlResult(UUID endpointId, int jobsFound) {}
    public record BackfillResult(int descriptionsBackfilled, int languageFiltered) {}
    public record RescoreResult(long deleted, long rescored) {}
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
