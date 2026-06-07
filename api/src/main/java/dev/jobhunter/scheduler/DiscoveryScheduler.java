package dev.jobhunter.scheduler;

import dev.jobhunter.discovery.DiscoveryService;
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
public class DiscoveryScheduler implements Job {

    private final DiscoveryService discoveryService;

    public DiscoveryScheduler(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
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
    }
}
