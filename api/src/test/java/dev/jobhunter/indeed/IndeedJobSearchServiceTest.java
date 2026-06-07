package dev.jobhunter.indeed;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.source.IndeedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IndeedJobSearchServiceTest {

    private AggregatorIngestionService aggregatorIngestionService;
    private IndeedSource indeedSource;
    private IndeedJobSearchService service;

    @BeforeEach
    void setUp() {
        aggregatorIngestionService = mock(AggregatorIngestionService.class);
        indeedSource = mock(IndeedSource.class);
        service = new IndeedJobSearchService(aggregatorIngestionService, indeedSource);
    }

    @Test
    @DisplayName("searchAndCreate delegates to AggregatorIngestionService")
    void shouldDelegateToIngestionService() {
        IngestionStats stats = new IngestionStats("indeed", 20, 0, 8, 5, 2, 0, 3000);
        when(aggregatorIngestionService.ingest(indeedSource)).thenReturn(stats);

        int[] result = service.searchAndCreate();

        verify(aggregatorIngestionService).ingest(indeedSource);
        assertThat(result[0]).isEqualTo(8); // created
        assertThat(result[1]).isEqualTo(5); // filtered
        assertThat(result[2]).isEqualTo(20); // fetched
    }

    @Test
    @DisplayName("searchAndCreate returns zeros when ingestion finds nothing")
    void shouldReturnZerosWhenNothingFound() {
        IngestionStats stats = new IngestionStats("indeed", 0, 0, 0, 0, 0, 0, 500);
        when(aggregatorIngestionService.ingest(indeedSource)).thenReturn(stats);

        int[] result = service.searchAndCreate();

        assertThat(result).containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("searchAndCreate passes IndeedSource to ingestion service")
    void shouldPassIndeedSource() {
        IngestionStats stats = new IngestionStats("indeed", 10, 0, 4, 3, 0, 0, 2000);
        when(aggregatorIngestionService.ingest(any())).thenReturn(stats);

        service.searchAndCreate();

        verify(aggregatorIngestionService).ingest(indeedSource);
        verifyNoMoreInteractions(aggregatorIngestionService);
    }
}
