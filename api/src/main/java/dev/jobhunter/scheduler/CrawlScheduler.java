package dev.jobhunter.scheduler;

import dev.jobhunter.service.CrawlService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@DisallowConcurrentExecution
public class CrawlScheduler implements Job {

    private final CrawlService crawlService;

    public CrawlScheduler(CrawlService crawlService) {
        this.crawlService = crawlService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Scheduled crawl starting");
        Instant start = Instant.now();

        try {
            int[] stats = crawlService.crawlAllDueEndpoints();
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("Scheduled crawl complete in {}s: endpoints={}, jobs={}, errors={}",
                    elapsed.toSeconds(), stats[0], stats[1], stats[2]);
        } catch (Exception e) {
            log.error("Scheduled crawl failed unexpectedly", e);
        }
    }
}
