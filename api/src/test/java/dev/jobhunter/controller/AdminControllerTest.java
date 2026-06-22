package dev.jobhunter.controller;

import dev.jobhunter.controller.AdminController.CrawlResult;
import dev.jobhunter.filter.LanguageFilter;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private CrawlService crawlService;
    @Mock private CareerEndpointRepository careerEndpointRepository;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private DiscoveryService discoveryService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(crawlService, careerEndpointRepository, scoringScheduler, discoveryService,
                null, null, null, null, null, null, Optional.empty(), List.of(), mock(LanguageFilter.class));
    }

    @Test
    void triggerCrawl_returnsStats() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{5, 12, 1});

        ResponseEntity<CrawlResult> response = controller.triggerCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().endpointsProcessed()).isEqualTo(5);
        assertThat(response.getBody().jobsFound()).isEqualTo(12);
        assertThat(response.getBody().errors()).isEqualTo(1);
        verify(crawlService).crawlAllDueEndpoints();
    }

    @Test
    void triggerCrawl_noEndpointsDue_returnsZeros() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{0, 0, 0});

        ResponseEntity<CrawlResult> response = controller.triggerCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().endpointsProcessed()).isEqualTo(0);
        assertThat(response.getBody().jobsFound()).isEqualTo(0);
        assertThat(response.getBody().errors()).isEqualTo(0);
    }

    @Test
    void triggerCrawl_allErrors_returnsErrorCount() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 0, 3});

        ResponseEntity<CrawlResult> response = controller.triggerCrawl();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().endpointsProcessed()).isEqualTo(3);
        assertThat(response.getBody().jobsFound()).isEqualTo(0);
        assertThat(response.getBody().errors()).isEqualTo(3);
    }
}
