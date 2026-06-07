package dev.jobhunter.controller;

import dev.jobhunter.controller.AdminController.ResolveResult;
import dev.jobhunter.discovery.DiscoveryService;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.service.CrawlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerResolveTest {

    @Mock private CrawlService crawlService;
    @Mock private CareerEndpointRepository careerEndpointRepository;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private DiscoveryService discoveryService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(crawlService, careerEndpointRepository, scoringScheduler, discoveryService,
                null, null, null, List.of());
    }

    @Test
    void triggerResolve_noLimit_callsWithNull() {
        when(discoveryService.resolveDiscoveredCompanies(null)).thenReturn(new int[]{10, 5, 2, 3});

        ResponseEntity<ResolveResult> response = controller.triggerResolve(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().total()).isEqualTo(10);
        assertThat(response.getBody().resolved()).isEqualTo(5);
        assertThat(response.getBody().failed()).isEqualTo(2);
        assertThat(response.getBody().skipped()).isEqualTo(3);
        verify(discoveryService).resolveDiscoveredCompanies(null);
    }

    @Test
    void triggerResolve_withLimit_passesLimit() {
        when(discoveryService.resolveDiscoveredCompanies(50)).thenReturn(new int[]{50, 30, 5, 15});

        ResponseEntity<ResolveResult> response = controller.triggerResolve(50);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().total()).isEqualTo(50);
        assertThat(response.getBody().resolved()).isEqualTo(30);
        assertThat(response.getBody().failed()).isEqualTo(5);
        assertThat(response.getBody().skipped()).isEqualTo(15);
        verify(discoveryService).resolveDiscoveredCompanies(50);
    }

    @Test
    void triggerResolve_noCompanies_returnsZeros() {
        when(discoveryService.resolveDiscoveredCompanies(null)).thenReturn(new int[]{0, 0, 0, 0});

        ResponseEntity<ResolveResult> response = controller.triggerResolve(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().total()).isEqualTo(0);
        assertThat(response.getBody().resolved()).isEqualTo(0);
    }

    @Test
    void triggerResolve_allFailed_returnsFailCount() {
        when(discoveryService.resolveDiscoveredCompanies(null)).thenReturn(new int[]{5, 0, 5, 0});

        ResponseEntity<ResolveResult> response = controller.triggerResolve(null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().total()).isEqualTo(5);
        assertThat(response.getBody().resolved()).isEqualTo(0);
        assertThat(response.getBody().failed()).isEqualTo(5);
        assertThat(response.getBody().skipped()).isEqualTo(0);
    }
}
