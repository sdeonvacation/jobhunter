package dev.jobhub.controller;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.service.CrawlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CrawlService crawlService;
    private final CareerEndpointRepository careerEndpointRepository;

    public AdminController(CrawlService crawlService, CareerEndpointRepository careerEndpointRepository) {
        this.crawlService = crawlService;
        this.careerEndpointRepository = careerEndpointRepository;
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

    public record CrawlResult(int endpointsProcessed, int jobsFound, int errors) {}

    public record SingleCrawlResult(UUID endpointId, int jobsFound) {}
}
