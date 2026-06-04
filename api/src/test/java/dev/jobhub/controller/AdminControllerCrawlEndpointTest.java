package dev.jobhub.controller;

import dev.jobhub.controller.AdminController.SingleCrawlResult;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.service.CrawlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerCrawlEndpointTest {

    @Mock private CrawlService crawlService;
    @Mock private CareerEndpointRepository careerEndpointRepository;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(crawlService, careerEndpointRepository);
    }

    @Test
    void crawlSingleEndpoint_existingEndpoint_returnsJobCount() {
        UUID endpointId = UUID.randomUUID();
        CareerEndpoint endpoint = CareerEndpoint.builder().id(endpointId).build();

        when(careerEndpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(crawlService.crawlEndpoint(endpoint)).thenReturn(7);

        ResponseEntity<SingleCrawlResult> response = controller.crawlSingleEndpoint(endpointId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().endpointId()).isEqualTo(endpointId);
        assertThat(response.getBody().jobsFound()).isEqualTo(7);
        verify(crawlService).crawlEndpoint(endpoint);
    }

    @Test
    void crawlSingleEndpoint_notFound_returns404() {
        UUID endpointId = UUID.randomUUID();
        when(careerEndpointRepository.findById(endpointId)).thenReturn(Optional.empty());

        ResponseEntity<SingleCrawlResult> response = controller.crawlSingleEndpoint(endpointId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(crawlService);
    }

    @Test
    void crawlSingleEndpoint_zeroJobs_returnsZero() {
        UUID endpointId = UUID.randomUUID();
        CareerEndpoint endpoint = CareerEndpoint.builder().id(endpointId).build();

        when(careerEndpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(crawlService.crawlEndpoint(endpoint)).thenReturn(0);

        ResponseEntity<SingleCrawlResult> response = controller.crawlSingleEndpoint(endpointId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobsFound()).isEqualTo(0);
    }

    @Test
    void triggerCrawl_stillWorks() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 10, 0});

        var response = controller.triggerCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().endpointsProcessed()).isEqualTo(3);
        assertThat(response.getBody().jobsFound()).isEqualTo(10);
        assertThat(response.getBody().errors()).isEqualTo(0);
    }
}
