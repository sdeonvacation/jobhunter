package dev.jobhunter.service;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.repository.CareerEndpointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CrawlHealthMonitor {

    private static final int EMPTY_THRESHOLD = 2;
    private static final int ERROR_THRESHOLD = 3;

    private final CareerEndpointRepository endpointRepository;

    // Tracks consecutive failure counts per endpoint
    private final Map<UUID, Integer> consecutiveEmptyCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveErrorCount = new ConcurrentHashMap<>();
    private final Set<UUID> unhealthyEndpoints = ConcurrentHashMap.newKeySet();

    public CrawlHealthMonitor(CareerEndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    /**
     * Called after each crawl cycle to evaluate endpoint health.
     * Detects endpoints with repeated EMPTY or ERROR statuses.
     */
    public void checkHealth() {
        List<CareerEndpoint> emptyEndpoints = endpointRepository
                .findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY);
        List<CareerEndpoint> errorEndpoints = endpointRepository
                .findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR);

        Set<UUID> currentlyFailing = new HashSet<>();

        for (CareerEndpoint ep : emptyEndpoints) {
            UUID id = ep.getId();
            currentlyFailing.add(id);
            int count = consecutiveEmptyCount.merge(id, 1, Integer::sum);

            if (count >= EMPTY_THRESHOLD) {
                unhealthyEndpoints.add(id);
                log.warn("CrawlHealth: endpoint {} ({}) returned EMPTY for {} consecutive runs - possible ATS migration",
                        id, ep.getUrl(), count);
            }
        }

        for (CareerEndpoint ep : errorEndpoints) {
            UUID id = ep.getId();
            currentlyFailing.add(id);
            int count = consecutiveErrorCount.merge(id, 1, Integer::sum);

            if (count >= ERROR_THRESHOLD) {
                unhealthyEndpoints.add(id);
                log.warn("CrawlHealth: endpoint {} ({}) has ERROR for {} consecutive runs - needs review",
                        id, ep.getUrl(), count);
            }
        }

        // Clear counters for endpoints that recovered (SUCCESS or other non-failing status)
        clearRecoveredEndpoints(currentlyFailing, consecutiveEmptyCount);
        clearRecoveredEndpoints(currentlyFailing, consecutiveErrorCount);

        // Remove from unhealthy if recovered
        unhealthyEndpoints.removeIf(id -> !currentlyFailing.contains(id)
                && !consecutiveEmptyCount.containsKey(id)
                && !consecutiveErrorCount.containsKey(id));

        if (!unhealthyEndpoints.isEmpty()) {
            log.info("CrawlHealth: {} unhealthy endpoints detected", unhealthyEndpoints.size());
        }
    }

    /**
     * Returns IDs of endpoints flagged as unhealthy.
     */
    public Set<UUID> getUnhealthyEndpoints() {
        return Collections.unmodifiableSet(unhealthyEndpoints);
    }

    /**
     * Returns the consecutive empty count for a given endpoint.
     */
    public int getConsecutiveEmptyCount(UUID endpointId) {
        return consecutiveEmptyCount.getOrDefault(endpointId, 0);
    }

    /**
     * Returns the consecutive error count for a given endpoint.
     */
    public int getConsecutiveErrorCount(UUID endpointId) {
        return consecutiveErrorCount.getOrDefault(endpointId, 0);
    }

    /**
     * Resets tracking state for an endpoint (e.g., after manual review).
     */
    public void acknowledge(UUID endpointId) {
        consecutiveEmptyCount.remove(endpointId);
        consecutiveErrorCount.remove(endpointId);
        unhealthyEndpoints.remove(endpointId);
    }

    private void clearRecoveredEndpoints(Set<UUID> currentlyFailing, Map<UUID, Integer> counterMap) {
        counterMap.keySet().removeIf(id -> !currentlyFailing.contains(id));
    }
}
