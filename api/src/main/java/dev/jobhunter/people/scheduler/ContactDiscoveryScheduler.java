package dev.jobhunter.people.scheduler;

import dev.jobhunter.people.service.ContactDiscoveryService;
import dev.jobhunter.people.service.RelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task for daily contact discovery and relationship maintenance.
 * Runs at a configurable time, respects daily search budget.
 */
@Slf4j
@Component
public class ContactDiscoveryScheduler {

    private final ContactDiscoveryService discoveryService;
    private final RelationshipService relationshipService;
    private final int dailySearchBudget;
    private final int ghostingThresholdDays;

    public ContactDiscoveryScheduler(ContactDiscoveryService discoveryService,
                                     RelationshipService relationshipService,
                                     @Value("${people.discovery.daily-search-budget:5}") int dailySearchBudget,
                                     @Value("${people.relationship.ghosting-threshold-days:14}") int ghostingThresholdDays) {
        this.discoveryService = discoveryService;
        this.relationshipService = relationshipService;
        this.dailySearchBudget = dailySearchBudget;
        this.ghostingThresholdDays = ghostingThresholdDays;
    }

    @Scheduled(cron = "${people.discovery.schedule:0 0 8 * * *}")
    public void runDailyDiscovery() {
        log.info("Starting daily contact discovery (budget: {} companies)", dailySearchBudget);
        try {
            int discovered = discoveryService.discoverTopPriority(dailySearchBudget);
            log.info("Daily discovery complete: {} contacts found", discovered);
        } catch (Exception e) {
            log.error("Daily discovery failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${people.relationship.ghosting-schedule:0 30 8 * * *}")
    public void runGhostingDetection() {
        log.info("Running ghosting detection (threshold: {} days)", ghostingThresholdDays);
        try {
            int ghosted = relationshipService.detectGhosting(ghostingThresholdDays);
            log.info("Ghosting detection complete: {} relationships marked ghosted", ghosted);
        } catch (Exception e) {
            log.error("Ghosting detection failed: {}", e.getMessage(), e);
        }
    }
}
