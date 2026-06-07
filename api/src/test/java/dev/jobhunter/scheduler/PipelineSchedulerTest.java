package dev.jobhunter.scheduler;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.source.SourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineSchedulerTest {

    @Mock private CrawlService crawlService;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private AggregatorIngestionService aggregatorIngestionService;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private SourceConfig enabledSource;
    @Mock private SourceConfig disabledSource;
    @Mock private SourceConfig notDueSource;

    private PipelineScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(enabledSource.isEnabled()).thenReturn(true);
        lenient().when(enabledSource.name()).thenReturn("linkedin");
        lenient().when(enabledSource.frequencyHours()).thenReturn(6);

        lenient().when(disabledSource.isEnabled()).thenReturn(false);
        lenient().when(disabledSource.name()).thenReturn("disabled-source");

        lenient().when(notDueSource.isEnabled()).thenReturn(true);
        lenient().when(notDueSource.name()).thenReturn("indeed");
        lenient().when(notDueSource.frequencyHours()).thenReturn(12);

        scheduler = new PipelineScheduler(crawlService, scoringScheduler,
                aggregatorIngestionService, aggregatorRunRepository,
                List.of(enabledSource, disabledSource, notDueSource));
    }

    @Test
    void runPipeline_crawlsAndIngestsEnabledDueSources() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{5, 20, 0});
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.empty());
        // notDueSource ran 1 hour ago with 12h frequency → not due
        AggregatorRun recentRun = AggregatorRun.builder()
                .sourceName("indeed")
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("indeed")).thenReturn(Optional.of(recentRun));
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 10, 5, 3, 2, 0, 0, 1500));

        scheduler.runPipeline();

        verify(crawlService).crawlAllDueEndpoints();
        verify(aggregatorIngestionService).ingest(enabledSource);
        verify(aggregatorIngestionService, never()).ingest(disabledSource);
        verify(aggregatorIngestionService, never()).ingest(notDueSource);
        verify(scoringScheduler).scoreAllUnscored();
    }

    @Test
    void runPipeline_noSources_stillCrawlsAndScores() {
        PipelineScheduler emptyScheduler = new PipelineScheduler(crawlService, scoringScheduler,
                aggregatorIngestionService, aggregatorRunRepository, List.of());
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 10, 0});

        emptyScheduler.runPipeline();

        verify(crawlService).crawlAllDueEndpoints();
        verify(aggregatorIngestionService, never()).ingest(any());
        verify(scoringScheduler).scoreAllUnscored();
    }

    @Test
    void runPipeline_sourceIngestionFailure_doesNotBlockPipeline() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{1, 5, 0});
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.empty());
        AggregatorRun recentRun = AggregatorRun.builder()
                .sourceName("indeed")
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("indeed")).thenReturn(Optional.of(recentRun));
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenThrow(new RuntimeException("Connection timeout"));

        assertThatCode(() -> scheduler.runPipeline()).doesNotThrowAnyException();

        verify(scoringScheduler).scoreAllUnscored();
    }

    @Test
    void runPipeline_crawlFailure_doesNotBlockPipeline() {
        when(crawlService.crawlAllDueEndpoints()).thenThrow(new RuntimeException("DB down"));
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.empty());
        AggregatorRun recentRun = AggregatorRun.builder()
                .sourceName("indeed")
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("indeed")).thenReturn(Optional.of(recentRun));
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 5, 2, 2, 1, 0, 0, 800));

        assertThatCode(() -> scheduler.runPipeline()).doesNotThrowAnyException();

        verify(scoringScheduler).scoreAllUnscored();
    }

    @Test
    void runPipeline_scoringFailure_doesNotPropagate() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{1, 1, 0});
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.empty());
        AggregatorRun recentRun = AggregatorRun.builder()
                .sourceName("indeed")
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("indeed")).thenReturn(Optional.of(recentRun));
        when(aggregatorIngestionService.ingest(enabledSource))
                .thenReturn(new IngestionStats("linkedin", 5, 2, 2, 1, 0, 0, 800));
        doThrow(new RuntimeException("Scoring error")).when(scoringScheduler).scoreAllUnscored();

        assertThatCode(() -> scheduler.runPipeline()).doesNotThrowAnyException();
    }

    @Test
    void isDue_neverRun_returnsTrue() {
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.empty());

        assertThat(scheduler.isDue(enabledSource)).isTrue();
    }

    @Test
    void isDue_ranRecentlyWithinFrequency_returnsFalse() {
        AggregatorRun recentRun = AggregatorRun.builder()
                .sourceName("linkedin")
                .lastRunAt(LocalDateTime.now().minusHours(2))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.of(recentRun));

        assertThat(scheduler.isDue(enabledSource)).isFalse();
    }

    @Test
    void isDue_ranLongAgo_returnsTrue() {
        AggregatorRun oldRun = AggregatorRun.builder()
                .sourceName("linkedin")
                .lastRunAt(LocalDateTime.now().minusHours(10))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.of(oldRun));

        assertThat(scheduler.isDue(enabledSource)).isTrue();
    }

    @Test
    void isDue_exactlyAtFrequencyBoundary_returnsFalse() {
        // Ran slightly less than frequencyHours ago → nextDue is slightly in the future → not yet past
        AggregatorRun boundaryRun = AggregatorRun.builder()
                .sourceName("linkedin")
                .lastRunAt(LocalDateTime.now().minusHours(6).plusSeconds(5))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.of(boundaryRun));

        // isAfter is strict: now is NOT after nextDue (5s in the future) → false
        assertThat(scheduler.isDue(enabledSource)).isFalse();
    }

    @Test
    void execute_delegatesToRunPipeline() {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{0, 0, 0});
        // All sources not due
        AggregatorRun recentLinkedin = AggregatorRun.builder()
                .sourceName("linkedin")
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .lastStatus("SUCCESS")
                .build();
        AggregatorRun recentIndeed = AggregatorRun.builder()
                .sourceName("indeed")
                .lastRunAt(LocalDateTime.now().minusHours(1))
                .lastStatus("SUCCESS")
                .build();
        when(aggregatorRunRepository.findBySourceName("linkedin")).thenReturn(Optional.of(recentLinkedin));
        when(aggregatorRunRepository.findBySourceName("indeed")).thenReturn(Optional.of(recentIndeed));

        assertThatCode(() -> scheduler.execute(null)).doesNotThrowAnyException();

        verify(crawlService).crawlAllDueEndpoints();
        verify(scoringScheduler).scoreAllUnscored();
    }
}
