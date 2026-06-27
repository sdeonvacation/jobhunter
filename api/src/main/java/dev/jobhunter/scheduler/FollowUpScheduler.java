package dev.jobhunter.scheduler;

import dev.jobhunter.dto.FollowUpDto;
import dev.jobhunter.service.FollowUpCadenceService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Daily follow-up overdue detection (9:00 AM). Transitions PENDING follow-ups past their
 * scheduled date to OVERDUE status.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class FollowUpScheduler implements Job {

    private final FollowUpCadenceService followUpCadenceService;

    public FollowUpScheduler(FollowUpCadenceService followUpCadenceService) {
        this.followUpCadenceService = followUpCadenceService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Follow-up overdue check starting");

        try {
            List<FollowUpDto> overdue = followUpCadenceService.getOverdue();
            log.info("Follow-up check: {} items now overdue", overdue.size());
        } catch (Exception e) {
            log.error("Follow-up overdue check failed", e);
        }
    }
}
