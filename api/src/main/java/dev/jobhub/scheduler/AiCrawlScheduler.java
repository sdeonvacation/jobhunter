package dev.jobhub.scheduler;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.service.CrawlService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@DisallowConcurrentExecution
public class AiCrawlScheduler implements Job {

    private final CrawlService crawlService;
    private final CareerEndpointRepository endpointRepository;
    private final int frequencyHours;
    private final int batchSize;
    private final boolean enabled;

    public AiCrawlScheduler(
            CrawlService crawlService,
            CareerEndpointRepository endpointRepository,
            @Value("${ai-crawl.frequency-hours:24}") int frequencyHours,
            @Value("${ai-crawl.batch-size:5}") int batchSize,
            @Value("${ai-crawl.enabled:true}") boolean enabled
    ) {
        this.crawlService = crawlService;
        this.endpointRepository = endpointRepository;
        this.frequencyHours = frequencyHours;
        this.batchSize = batchSize;
        this.enabled = enabled;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (!enabled) {
            log.debug("AI crawl disabled, skipping");
            return;
        }

        log.info("AI crawl starting");
        Instant start = Instant.now();

        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(frequencyHours);
            List<CareerEndpoint> endpoints = endpointRepository.findCustomEndpointsDueForCrawl(
                    cutoff, org.springframework.data.domain.PageRequest.of(0, batchSize));

            if (endpoints.isEmpty()) {
                log.info("AI crawl: no CUSTOM endpoints due");
                return;
            }

            int crawled = 0;
            int totalJobs = 0;
            int errors = 0;

            for (CareerEndpoint endpoint : endpoints) {
                try {
                    int jobsFound = crawlService.crawlEndpoint(endpoint);
                    totalJobs += jobsFound;
                    crawled++;
                } catch (Exception e) {
                    errors++;
                    log.error("AI crawl failed for endpoint [{}] (company: {}): {}",
                            endpoint.getId(),
                            endpoint.getCompany() != null ? endpoint.getCompany().getName() : "unknown",
                            e.getMessage());
                }
            }

            Duration elapsed = Duration.between(start, Instant.now());
            log.info("AI crawl complete in {}s: endpoints={}, jobs={}, errors={}",
                    elapsed.toSeconds(), crawled, totalJobs, errors);

        } catch (Exception e) {
            log.error("AI crawl failed unexpectedly", e);
        }
    }
}
