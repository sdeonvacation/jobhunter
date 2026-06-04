package dev.jobhub.controller;

import dev.jobhub.service.CrawlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CrawlService crawlService;

    public AdminController(CrawlService crawlService) {
        this.crawlService = crawlService;
    }

    @PostMapping("/crawl")
    public ResponseEntity<CrawlResult> triggerCrawl() {
        int[] stats = crawlService.crawlAllDueEndpoints();
        return ResponseEntity.ok(new CrawlResult(stats[0], stats[1], stats[2]));
    }

    public record CrawlResult(int endpointsProcessed, int jobsFound, int errors) {}
}
