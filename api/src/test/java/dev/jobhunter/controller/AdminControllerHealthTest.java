package dev.jobhunter.controller;

import dev.jobhunter.controller.AdminController.AggregatorHealth;
import dev.jobhunter.controller.AdminController.HealthReport;
import dev.jobhunter.discovery.DiscoveryService;
import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.scheduler.PipelineScheduler;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.service.CrawlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerHealthTest {

    @Mock private CrawlService crawlService;
    @Mock private CareerEndpointRepository careerEndpointRepository;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private DiscoveryService discoveryService;
    @Mock private PipelineScheduler pipelineScheduler;
    @Mock private AggregatorIngestionService aggregatorIngestionService;
    @Mock private AggregatorRunRepository aggregatorRunRepository;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(crawlService, careerEndpointRepository, scoringScheduler,
                discoveryService, pipelineScheduler, aggregatorIngestionService,
                aggregatorRunRepository, null, null, List.of());
    }

    @Test
    void getHealth_includesAggregatorIssues_whenErrorStatus() {
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR)).thenReturn(List.of());
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY)).thenReturn(List.of());
        when(careerEndpointRepository.countByIsActiveTrue()).thenReturn(10L);
        when(careerEndpointRepository.countByIsActiveTrueAndLastCrawlStatusIsNull()).thenReturn(2L);

        AggregatorRun errorRun = AggregatorRun.builder()
                .id(UUID.randomUUID())
                .sourceName("stepstone")
                .lastStatus("ERROR")
                .jobsFetched(0)
                .errors(1)
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .elapsedMs(1500)
                .build();

        AggregatorRun successRun = AggregatorRun.builder()
                .id(UUID.randomUUID())
                .sourceName("arbeitnow")
                .lastStatus("SUCCESS")
                .jobsFetched(25)
                .errors(0)
                .lastRunAt(LocalDateTime.now())
                .elapsedMs(3000)
                .build();

        when(aggregatorRunRepository.findAll()).thenReturn(List.of(errorRun, successRun));

        ResponseEntity<HealthReport> response = controller.getHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HealthReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.aggregatorIssues()).hasSize(1);
        AggregatorHealth issue = report.aggregatorIssues().get(0);
        assertThat(issue.name()).isEqualTo("stepstone");
        assertThat(issue.status()).isEqualTo("ERROR");
        assertThat(issue.errors()).isEqualTo(1);
    }

    @Test
    void getHealth_includesAggregatorIssues_whenEmptyStatus() {
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR)).thenReturn(List.of());
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY)).thenReturn(List.of());
        when(careerEndpointRepository.countByIsActiveTrue()).thenReturn(5L);
        when(careerEndpointRepository.countByIsActiveTrueAndLastCrawlStatusIsNull()).thenReturn(0L);

        AggregatorRun emptyRun = AggregatorRun.builder()
                .id(UUID.randomUUID())
                .sourceName("indeed")
                .lastStatus("EMPTY")
                .jobsFetched(0)
                .errors(0)
                .lastRunAt(LocalDateTime.now().minusMinutes(30))
                .elapsedMs(800)
                .build();

        when(aggregatorRunRepository.findAll()).thenReturn(List.of(emptyRun));

        ResponseEntity<HealthReport> response = controller.getHealth();

        HealthReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.aggregatorIssues()).hasSize(1);
        assertThat(report.aggregatorIssues().get(0).name()).isEqualTo("indeed");
        assertThat(report.aggregatorIssues().get(0).status()).isEqualTo("EMPTY");
    }

    @Test
    void getHealth_includesAggregatorIssues_whenSuccessButZeroFetched() {
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR)).thenReturn(List.of());
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY)).thenReturn(List.of());
        when(careerEndpointRepository.countByIsActiveTrue()).thenReturn(5L);
        when(careerEndpointRepository.countByIsActiveTrueAndLastCrawlStatusIsNull()).thenReturn(0L);

        AggregatorRun zeroFetchRun = AggregatorRun.builder()
                .id(UUID.randomUUID())
                .sourceName("linkedin")
                .lastStatus("SUCCESS")
                .jobsFetched(0)
                .errors(0)
                .lastRunAt(LocalDateTime.now())
                .elapsedMs(500)
                .build();

        when(aggregatorRunRepository.findAll()).thenReturn(List.of(zeroFetchRun));

        ResponseEntity<HealthReport> response = controller.getHealth();

        HealthReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.aggregatorIssues()).hasSize(1);
        assertThat(report.aggregatorIssues().get(0).name()).isEqualTo("linkedin");
        assertThat(report.aggregatorIssues().get(0).status()).isEqualTo("SUCCESS");
        assertThat(report.aggregatorIssues().get(0).jobsFetched()).isEqualTo(0);
    }

    @Test
    void getHealth_excludesHealthyAggregators() {
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR)).thenReturn(List.of());
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY)).thenReturn(List.of());
        when(careerEndpointRepository.countByIsActiveTrue()).thenReturn(5L);
        when(careerEndpointRepository.countByIsActiveTrueAndLastCrawlStatusIsNull()).thenReturn(0L);

        AggregatorRun healthyRun = AggregatorRun.builder()
                .id(UUID.randomUUID())
                .sourceName("arbeitnow")
                .lastStatus("SUCCESS")
                .jobsFetched(42)
                .errors(0)
                .lastRunAt(LocalDateTime.now())
                .elapsedMs(2000)
                .build();

        when(aggregatorRunRepository.findAll()).thenReturn(List.of(healthyRun));

        ResponseEntity<HealthReport> response = controller.getHealth();

        HealthReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.aggregatorIssues()).isEmpty();
    }

    @Test
    void getHealth_returnsEmptyAggregatorIssues_whenNoRuns() {
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.ERROR)).thenReturn(List.of());
        when(careerEndpointRepository.findByIsActiveTrueAndLastCrawlStatus(CrawlStatus.EMPTY)).thenReturn(List.of());
        when(careerEndpointRepository.countByIsActiveTrue()).thenReturn(0L);
        when(careerEndpointRepository.countByIsActiveTrueAndLastCrawlStatusIsNull()).thenReturn(0L);

        when(aggregatorRunRepository.findAll()).thenReturn(List.of());

        ResponseEntity<HealthReport> response = controller.getHealth();

        HealthReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.aggregatorIssues()).isEmpty();
        assertThat(report.totalEndpoints()).isEqualTo(0);
    }
}
