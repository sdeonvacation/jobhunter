package dev.jobhunter.controller;

import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.ingestion.AggregatorIngestionService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerScoringTriggerTest {

    @Mock private CrawlService crawlService;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private AggregatorIngestionService aggregatorIngestionService;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private SourceConfig enabledSource;
    @Mock private SourceConfig disabledSource;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(crawlService, null, scoringScheduler, null,
                null, null, aggregatorIngestionService, aggregatorRunRepository, null, null,
                null, Optional.empty(), List.of(), mock(LanguageFilter.class), List.of(), List.of());
    }

    @Test
    @DisplayName("triggerCrawl should call scoreAllUnscored after crawling")
    void triggerCrawl_callsScoringAfterCrawl() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 8, 0});

        var response = controller.triggerCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        InOrder inOrder = inOrder(crawlService, scoringScheduler);
        inOrder.verify(crawlService, timeout(1000)).crawlAllDueEndpoints();
        inOrder.verify(scoringScheduler, timeout(1000)).scoreAllUnscored();
    }

    @Test
    @DisplayName("triggerAggregatorCrawl should asynchronously ingest enabled sources before scoring")
    void triggerAggregatorCrawl_callsScoringAfterEnabledIngestion() {
        when(enabledSource.isEnabled()).thenReturn(true);
        when(disabledSource.isEnabled()).thenReturn(false);
        controller = new AdminController(crawlService, null, scoringScheduler, null,
                null, null, aggregatorIngestionService, aggregatorRunRepository, null, null,
                null, Optional.empty(), List.of(enabledSource, disabledSource),
                mock(LanguageFilter.class), List.of(), List.of());

        var response = controller.triggerAggregatorCrawl();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo("Aggregator crawl triggered");

        verify(aggregatorIngestionService, timeout(1000)).ingest(enabledSource);
        verify(aggregatorIngestionService, never()).ingest(disabledSource);
        verify(scoringScheduler, timeout(1000)).scoreAllUnscored();

        var inOrder = inOrder(aggregatorIngestionService, scoringScheduler);
        inOrder.verify(aggregatorIngestionService).ingest(enabledSource);
        inOrder.verify(scoringScheduler).scoreAllUnscored();
    }
}
