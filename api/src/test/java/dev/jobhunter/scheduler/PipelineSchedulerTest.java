package dev.jobhunter.scheduler;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.service.ScoringService;
import dev.jobhunter.source.SourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineSchedulerTest {

    @Mock private CrawlService crawlService;
    @Mock private ScoringService scoringService;
    @Mock private AggregatorIngestionService aggregatorIngestionService;
    @Mock private SourceConfig enabledSource;
    @Mock private SourceConfig disabledSource;

    private PipelineScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(enabledSource.isEnabled()).thenReturn(true);
        lenient().when(enabledSource.name()).thenReturn("linkedin");

        lenient().when(disabledSource.isEnabled()).thenReturn(false);
        lenient().when(disabledSource.name()).thenReturn("disabled-source");

        scheduler = new PipelineScheduler(crawlService, scoringService,
                aggregatorIngestionService, List.of(enabledSource, disabledSource));
    }

    @Test
    void runPipeline_crawlsAndIngestsEnabledSources() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{5, 20, 0});
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 10, 5, 3, 2, 0, 0, 1500));

        scheduler.runPipeline();

        verify(crawlService).crawlAllDueEndpoints();
        verify(aggregatorIngestionService).ingest(enabledSource);
        verify(aggregatorIngestionService, never()).ingest(disabledSource);
        verify(scoringService).scoreAllUnscored();
    }

    @Test
    void runPipeline_noSources_stillCrawlsAndScores() {
        PipelineScheduler emptyScheduler = new PipelineScheduler(crawlService, scoringService,
                aggregatorIngestionService, List.of());
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 10, 0});

        emptyScheduler.runPipeline();

        verify(crawlService).crawlAllDueEndpoints();
        verify(aggregatorIngestionService, never()).ingest(any());
        verify(scoringService).scoreAllUnscored();
    }

    @Test
    void runPipeline_sourceIngestionFailure_doesNotBlockPipeline() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{1, 5, 0});
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenThrow(new RuntimeException("Connection timeout"));

        assertThatCode(() -> scheduler.runPipeline()).doesNotThrowAnyException();

        verify(scoringService).scoreAllUnscored();
    }

    @Test
    void runPipeline_crawlFailure_doesNotBlockPipeline() {
        when(crawlService.crawlAllDueEndpoints()).thenThrow(new RuntimeException("DB down"));
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 5, 2, 2, 1, 0, 0, 800));

        assertThatCode(() -> scheduler.runPipeline()).doesNotThrowAnyException();

        verify(scoringService).scoreAllUnscored();
    }

    @Test
    void runPipeline_scoringFailure_doesNotPropagate() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{1, 1, 0});
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 5, 2, 2, 1, 0, 0, 800));
        doThrow(new RuntimeException("Scoring error")).when(scoringService).scoreAllUnscored();

        assertThatCode(() -> scheduler.runPipeline()).doesNotThrowAnyException();
    }

    @Test
    void execute_delegatesToRunPipeline() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{0, 0, 0});
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 5, 2, 2, 1, 0, 0, 800));

        assertThatCode(() -> scheduler.execute(null)).doesNotThrowAnyException();

        verify(crawlService).crawlAllDueEndpoints();
        verify(scoringService).scoreAllUnscored();
    }
}
