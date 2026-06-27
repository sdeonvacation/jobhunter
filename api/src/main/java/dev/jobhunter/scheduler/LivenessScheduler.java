package dev.jobhunter.scheduler;

import dev.jobhunter.service.LivenessCheckService;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Daily liveness re-check (3:00 AM). Verifies applied job postings are still active.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class LivenessScheduler implements Job {

    private static final int RECHECK_HOURS = 72;

    private final LivenessCheckService livenessCheckService;
    private final EntityManager entityManager;

    public LivenessScheduler(LivenessCheckService livenessCheckService, EntityManager entityManager) {
        this.livenessCheckService = livenessCheckService;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Liveness check starting");

        List<UUID> jobIds = findJobsNeedingCheck();
        if (jobIds.isEmpty()) {
            log.info("Liveness check complete: 0 jobs needed checking");
            return;
        }

        int checked = 0;
        int expired = 0;
        int errors = 0;

        for (UUID jobId : jobIds) {
            try {
                var result = livenessCheckService.checkLiveness(jobId);
                checked++;
                if ("EXPIRED".equals(result.status())) {
                    expired++;
                }
            } catch (Exception e) {
                errors++;
                log.warn("Liveness check failed for job {}: {}", jobId, e.getMessage());
            }
        }

        log.info("Liveness check complete: {} jobs checked, {} expired, {} errors",
                checked, expired, errors);
    }

    @SuppressWarnings("unchecked")
    List<UUID> findJobsNeedingCheck() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RECHECK_HOURS);
        return entityManager.createNativeQuery(
                        "SELECT id FROM job_posting WHERE applied = true " +
                                "AND (last_liveness_check IS NULL OR last_liveness_check < :cutoff)")
                .setParameter("cutoff", cutoff)
                .getResultList();
    }
}
