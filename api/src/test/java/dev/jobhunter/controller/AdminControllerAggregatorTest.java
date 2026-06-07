package dev.jobhunter.controller;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.scheduler.PipelineScheduler;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.source.SourceConfig;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerAggregatorTest {

    @Mock private CrawlService crawlService;
    @Mock private CareerEndpointRepository careerEndpointRepository;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private PipelineScheduler pipelineScheduler;
    @Mock private AggregatorIngestionService aggregatorIngestionService;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private FetchStrategy mockStrategy;

    private AdminController controller;
    private SourceConfig linkedinSource;
    private SourceConfig indeedSource;

    @BeforeEach
    void setUp() {
        linkedinSource = new TestSourceConfig("linkedin", JobSource.LINKEDIN, 6, true);
        indeedSource = new TestSourceConfig("indeed", JobSource.INDEED, 12, false);

        controller = new AdminController(crawlService, careerEndpointRepository,
                scoringScheduler, null, pipelineScheduler,
                aggregatorIngestionService, aggregatorRunRepository,
                List.of(linkedinSource, indeedSource));
    }

    @Test
    void triggerAggregation_existingSource_returnsStats() {
        IngestionStats expected = new IngestionStats("linkedin", 15, 8, 5, 3, 2, 0, 2000);
        when(aggregatorIngestionService.ingest(linkedinSource)).thenReturn(expected);

        ResponseEntity<?> response = controller.triggerAggregation("linkedin");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(aggregatorIngestionService).ingest(linkedinSource);
    }

    @Test
    void triggerAggregation_unknownSource_returns404() {
        ResponseEntity<?> response = controller.triggerAggregation("nonexistent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(aggregatorIngestionService, never()).ingest(any());
    }

    @Test
    void triggerAggregation_disabledSource_stillTriggered() {
        IngestionStats expected = new IngestionStats("indeed", 10, 4, 3, 2, 1, 0, 1500);
        when(aggregatorIngestionService.ingest(indeedSource)).thenReturn(expected);

        ResponseEntity<?> response = controller.triggerAggregation("indeed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void listAggregators_returnsAllSourcesWithStatus() {
        AggregatorRun linkedinRun = AggregatorRun.builder()
                .sourceName("linkedin")
                .lastRunAt(LocalDateTime.of(2024, 6, 1, 10, 0))
                .lastStatus("SUCCESS")
                .jobsFetched(20)
                .jobsCreated(8)
                .errors(0)
                .elapsedMs(3000)
                .build();
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.of(linkedinRun));
        when(aggregatorRunRepository.findBySourceName("indeed")).thenReturn(Optional.empty());

        ResponseEntity<List<AdminController.AggregatorStatus>> response = controller.listAggregators();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AdminController.AggregatorStatus> statuses = response.getBody();
        assertThat(statuses).hasSize(2);

        AdminController.AggregatorStatus linkedin = statuses.get(0);
        assertThat(linkedin.name()).isEqualTo("linkedin");
        assertThat(linkedin.sourceType()).isEqualTo("LINKEDIN");
        assertThat(linkedin.frequencyHours()).isEqualTo(6);
        assertThat(linkedin.enabled()).isTrue();
        assertThat(linkedin.lastRunAt()).isEqualTo(LocalDateTime.of(2024, 6, 1, 10, 0));
        assertThat(linkedin.lastStatus()).isEqualTo("SUCCESS");
        assertThat(linkedin.jobsFetched()).isEqualTo(20);
        assertThat(linkedin.jobsCreated()).isEqualTo(8);

        AdminController.AggregatorStatus indeed = statuses.get(1);
        assertThat(indeed.name()).isEqualTo("indeed");
        assertThat(indeed.sourceType()).isEqualTo("INDEED");
        assertThat(indeed.frequencyHours()).isEqualTo(12);
        assertThat(indeed.enabled()).isFalse();
        assertThat(indeed.lastRunAt()).isNull();
        assertThat(indeed.lastStatus()).isNull();
    }

    @Test
    void listAggregators_noSources_returnsEmptyList() {
        AdminController emptyController = new AdminController(crawlService, careerEndpointRepository,
                scoringScheduler, null, pipelineScheduler,
                aggregatorIngestionService, aggregatorRunRepository, List.of());

        ResponseEntity<List<AdminController.AggregatorStatus>> response = emptyController.listAggregators();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    /**
     * Test implementation of SourceConfig for unit testing.
     */
    private class TestSourceConfig implements SourceConfig {
        private final String name;
        private final JobSource sourceType;
        private final int frequencyHours;
        private final boolean enabled;

        TestSourceConfig(String name, JobSource sourceType, int frequencyHours, boolean enabled) {
            this.name = name;
            this.sourceType = sourceType;
            this.frequencyHours = frequencyHours;
            this.enabled = enabled;
        }

        @Override public String name() { return name; }
        @Override public JobSource sourceType() { return sourceType; }
        @Override public DiscoverySource discoverySource() { return DiscoverySource.JOBSPY; }
        @Override public FetchStrategy strategy() { return mockStrategy; }
        @Override public FetchContext buildContext() { return null; }
        @Override public int frequencyHours() { return frequencyHours; }
        @Override public boolean isEnabled() { return enabled; }
    }
}
