package dev.jobhunter.controller;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.source.SourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerScoringTriggerTest {

    @Mock private CrawlService crawlService;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private AggregatorIngestionService aggregatorIngestionService;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private SourceConfig sourceConfig;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(crawlService, null, scoringScheduler, null,
                null, aggregatorIngestionService, aggregatorRunRepository, null, null, List.of(sourceConfig));
    }

    @Test
    @DisplayName("triggerCrawl should call scoreAllUnscored after crawling")
    void triggerCrawl_callsScoringAfterCrawl() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 8, 0});

        var response = controller.triggerCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        InOrder inOrder = inOrder(crawlService, scoringScheduler);
        inOrder.verify(crawlService).crawlAllDueEndpoints();
        inOrder.verify(scoringScheduler).scoreAllUnscored();
    }

    @Test
    @DisplayName("triggerAggregatorCrawl should call scoreAllUnscored after ingestion")
    void triggerAggregatorCrawl_callsScoringAfterIngestion() {
        IngestionStats stats = new IngestionStats("test", 5, 0, 3, 1, 0, 0, 1000);
        when(aggregatorIngestionService.ingest(sourceConfig)).thenReturn(stats);

        var response = controller.triggerAggregatorCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);

        InOrder inOrder = inOrder(aggregatorIngestionService, scoringScheduler);
        inOrder.verify(aggregatorIngestionService).ingest(sourceConfig);
        inOrder.verify(scoringScheduler).scoreAllUnscored();
    }
}
