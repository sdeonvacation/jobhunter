package dev.jobhub.controller;

import dev.jobhub.discovery.DiscoveryService;
import dev.jobhub.linkedin.LinkedInJobSearchService;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.CrawlStatus;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.scheduler.ScoringScheduler;
import dev.jobhub.service.CrawlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CrawlService crawlService;
    private final CareerEndpointRepository careerEndpointRepository;
    private final ScoringScheduler scoringScheduler;
    private final DiscoveryService discoveryService;

    @Autowired(required = false)
    private LinkedInJobSearchService linkedInJobSearchService;

    public AdminController(CrawlService crawlService, CareerEndpointRepository careerEndpointRepository,
                           ScoringScheduler scoringScheduler, DiscoveryService discoveryService) {
        this.crawlService = crawlService;
        this.careerEndpointRepository = careerEndpointRepository;
        this.scoringScheduler = scoringScheduler;
        this.discoveryService = discoveryService;
    }

    @PostMapping("/crawl")
    public ResponseEntity<CrawlResult> triggerCrawl() {
        int[] stats = crawlService.crawlAllDueEndpoints();
        return ResponseEntity.ok(new CrawlResult(stats[0], stats[1], stats[2]));
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

    @PostMapping("/linkedin-search")
    public ResponseEntity<?> triggerLinkedInSearch() {
        if (linkedInJobSearchService == null) {
            return ResponseEntity.badRequest().body("LinkedIn MCP integration is not enabled");
        }
        int[] stats = linkedInJobSearchService.searchAndMatch();
        return ResponseEntity.ok(new LinkedInSearchResult(stats[0], stats[1], stats[2]));
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

        return ResponseEntity.ok(new HealthReport(totalActive, totalErrored, totalEmpty, neverCrawled, errors, empties));
    }

    public record CrawlResult(int endpointsProcessed, int jobsFound, int errors) {}
    public record SingleCrawlResult(UUID endpointId, int jobsFound) {}
    public record BackfillResult(int descriptionsBackfilled) {}
    public record ResolveResult(int total, int resolved, int failed, int skipped) {}
    public record DiscoverResult(int providersQueried, int companiesFound, int newCompanies) {}
    public record LinkedInSearchResult(int enriched, int created, int searches) {}

    public record EndpointHealth(
            UUID id, String companyName, String atsType, String atsSlug,
            String url, String status, String errorMessage,
            int consecutiveErrors, LocalDateTime lastCrawledAt) {}

    public record HealthReport(
            long totalEndpoints, long errored, long empty, long neverCrawled,
            List<EndpointHealth> errors, List<EndpointHealth> empties) {}
}
