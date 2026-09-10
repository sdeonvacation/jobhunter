package dev.jobhunter.scheduler;

import dev.jobhunter.strategy.aggregator.GlobalMoveSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

/**
 * Replays the GlobalMove session cookie against a cheap authenticated endpoint
 * so the sliding ~2h session never lapses while the API is up. Thin wrapper:
 * all HTTP and state handling lives in {@link GlobalMoveSessionManager}.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class GlobalMoveKeepaliveJob implements Job {

    private final GlobalMoveSessionManager sessionManager;

    public GlobalMoveKeepaliveJob(GlobalMoveSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (!sessionManager.isConfigured()) {
            log.debug("GlobalMove keepalive skipped: session not configured");
            return;
        }

        try {
            sessionManager.ping();
        } catch (Exception e) {
            // ping() is contractually non-throwing; guard so execute() never fails the scheduler.
            log.error("GlobalMove keepalive ping threw unexpectedly", e);
            return;
        }

        if (sessionManager.isValid()) {
            log.info("GlobalMove keepalive complete: session still valid");
        } else {
            log.warn("GlobalMove keepalive complete: session is DEAD, operator re-capture required");
        }
    }
}
