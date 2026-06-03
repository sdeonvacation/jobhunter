package dev.jobhub.service;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.CrawlStatus;
import dev.jobhub.repository.CareerEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawlHealthMonitorTest {

    @Mock
    private CareerEndpointRepository endpointRepository;

    private CrawlHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new CrawlHealthMonitor(endpointRepository);
    }

    @Test
    void checkHealth_noFailingEndpoints_noUnhealthy() {
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of());
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of());

        monitor.checkHealth();

        assertThat(monitor.getUnhealthyEndpoints()).isEmpty();
    }

    @Test
    void checkHealth_emptyOnce_notYetUnhealthy() {
        UUID id = UUID.randomUUID();
        var endpoint = buildEndpoint(id, CrawlStatus.EMPTY);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of(endpoint));
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of());

        monitor.checkHealth();

        assertThat(monitor.getUnhealthyEndpoints()).isEmpty();
        assertThat(monitor.getConsecutiveEmptyCount(id)).isEqualTo(1);
    }

    @Test
    void checkHealth_emptyTwice_becomesUnhealthy() {
        UUID id = UUID.randomUUID();
        var endpoint = buildEndpoint(id, CrawlStatus.EMPTY);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of(endpoint));
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of());

        monitor.checkHealth(); // count=1
        monitor.checkHealth(); // count=2, threshold reached

        assertThat(monitor.getUnhealthyEndpoints()).contains(id);
        assertThat(monitor.getConsecutiveEmptyCount(id)).isEqualTo(2);
    }

    @Test
    void checkHealth_errorThreeTimes_becomesUnhealthy() {
        UUID id = UUID.randomUUID();
        var endpoint = buildEndpoint(id, CrawlStatus.ERROR);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of());
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of(endpoint));

        monitor.checkHealth(); // count=1
        monitor.checkHealth(); // count=2
        monitor.checkHealth(); // count=3, threshold reached

        assertThat(monitor.getUnhealthyEndpoints()).contains(id);
        assertThat(monitor.getConsecutiveErrorCount(id)).isEqualTo(3);
    }

    @Test
    void checkHealth_errorTwice_notYetUnhealthy() {
        UUID id = UUID.randomUUID();
        var endpoint = buildEndpoint(id, CrawlStatus.ERROR);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of());
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of(endpoint));

        monitor.checkHealth();
        monitor.checkHealth();

        assertThat(monitor.getUnhealthyEndpoints()).isEmpty();
        assertThat(monitor.getConsecutiveErrorCount(id)).isEqualTo(2);
    }

    @Test
    void checkHealth_endpointRecovers_removedFromUnhealthy() {
        UUID id = UUID.randomUUID();
        var endpoint = buildEndpoint(id, CrawlStatus.EMPTY);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of(endpoint));
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of());

        // Become unhealthy
        monitor.checkHealth();
        monitor.checkHealth();
        assertThat(monitor.getUnhealthyEndpoints()).contains(id);

        // Endpoint recovers (no longer in EMPTY list)
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of());

        monitor.checkHealth();

        assertThat(monitor.getUnhealthyEndpoints()).doesNotContain(id);
        assertThat(monitor.getConsecutiveEmptyCount(id)).isEqualTo(0);
    }

    @Test
    void checkHealth_multipleEndpoints_trackedIndependently() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        var endpoint1 = buildEndpoint(id1, CrawlStatus.EMPTY);
        var endpoint2 = buildEndpoint(id2, CrawlStatus.ERROR);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of(endpoint1));
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of(endpoint2));

        // First run
        monitor.checkHealth();
        assertThat(monitor.getConsecutiveEmptyCount(id1)).isEqualTo(1);
        assertThat(monitor.getConsecutiveErrorCount(id2)).isEqualTo(1);

        // Second run - endpoint1 hits threshold (2), endpoint2 not yet (needs 3)
        monitor.checkHealth();
        assertThat(monitor.getUnhealthyEndpoints()).contains(id1);
        assertThat(monitor.getUnhealthyEndpoints()).doesNotContain(id2);
    }

    @Test
    void acknowledge_resetsTracking() {
        UUID id = UUID.randomUUID();
        var endpoint = buildEndpoint(id, CrawlStatus.EMPTY);

        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY))
                .thenReturn(List.of(endpoint));
        when(endpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR))
                .thenReturn(List.of());

        monitor.checkHealth();
        monitor.checkHealth();
        assertThat(monitor.getUnhealthyEndpoints()).contains(id);

        monitor.acknowledge(id);

        assertThat(monitor.getUnhealthyEndpoints()).doesNotContain(id);
        assertThat(monitor.getConsecutiveEmptyCount(id)).isEqualTo(0);
    }

    @Test
    void getUnhealthyEndpoints_returnsUnmodifiableSet() {
        var result = monitor.getUnhealthyEndpoints();
        assertThat(result).isNotNull();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> result.add(UUID.randomUUID())
        );
    }

    private CareerEndpoint buildEndpoint(UUID id, CrawlStatus status) {
        return CareerEndpoint.builder()
                .id(id)
                .url("https://example.com/careers")
                .lastCrawlStatus(status)
                .isActive(true)
                .build();
    }
}
