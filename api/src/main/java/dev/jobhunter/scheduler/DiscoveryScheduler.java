package dev.jobhunter.scheduler;

import dev.jobhunter.discovery.DiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@DisallowConcurrentExecution
public class DiscoveryScheduler implements Job {

    private final DiscoveryService discoveryService;
    private final int resolveLimit;

    public DiscoveryScheduler(DiscoveryService discoveryService,
                              @Value("${discovery.resolve-limit:50}") int resolveLimit) {
        this.discoveryService = discoveryService;
        this.resolveLimit = resolveLimit;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Scheduled discovery starting");
        Instant start = Instant.now();

        try {
            int[] stats = discoveryService.runDiscovery();
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("Scheduled discovery complete in {}s: total={}, registered={}, existing={}, failed={}",
                    elapsed.toSeconds(), stats[0], stats[1], stats[2], stats[3]);
        } catch (Exception e) {
            log.error("Scheduled discovery failed unexpectedly", e);
        }

        // Drain the DISCOVERED backlog: resolve endpoints for companies without any
        // (the only step that promotes DISCOVERED -> ACTIVE). Previously manual-only.
        try {
            int[] resolveStats = discoveryService.resolveDiscoveredCompanies(resolveLimit);
            log.info("Scheduled endpoint resolution complete: total={}, resolved={}, failed={}, skipped={}",
                    resolveStats[0], resolveStats[1], resolveStats[2], resolveStats[3]);
        } catch (Exception e) {
            log.error("Scheduled endpoint resolution failed unexpectedly", e);
        }
    }
}
