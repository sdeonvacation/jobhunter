package dev.jobhunter.linkedin;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.source.LinkedInSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LinkedInJobSearchServiceTest {

    private AggregatorIngestionService aggregatorIngestionService;
    private LinkedInSource linkedInSource;
    private LinkedInJobSearchService service;

    @BeforeEach
    void setUp() {
        aggregatorIngestionService = mock(AggregatorIngestionService.class);
        linkedInSource = mock(LinkedInSource.class);
        service = new LinkedInJobSearchService(aggregatorIngestionService, linkedInSource);
    }

    @Test
    @DisplayName("searchAndMatch delegates to AggregatorIngestionService")
    void shouldDelegateToIngestionService() {
        IngestionStats stats = new IngestionStats("linkedin", 15, 3, 5, 2, 1, 0, 1200);
        when(aggregatorIngestionService.ingest(linkedInSource)).thenReturn(stats);

        int[] result = service.searchAndMatch();

        verify(aggregatorIngestionService).ingest(linkedInSource);
        assertThat(result[0]).isEqualTo(3); // enriched
        assertThat(result[1]).isEqualTo(5); // created
        assertThat(result[2]).isEqualTo(15); // fetched
    }

    @Test
    @DisplayName("searchAndMatch returns zeros when ingestion finds nothing")
    void shouldReturnZerosWhenNothingFound() {
        IngestionStats stats = new IngestionStats("linkedin", 0, 0, 0, 0, 0, 0, 500);
        when(aggregatorIngestionService.ingest(linkedInSource)).thenReturn(stats);

        int[] result = service.searchAndMatch();

        assertThat(result).containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("searchAndMatch passes LinkedInSource to ingestion service")
    void shouldPassLinkedInSource() {
        IngestionStats stats = new IngestionStats("linkedin", 10, 2, 4, 1, 0, 0, 800);
        when(aggregatorIngestionService.ingest(any())).thenReturn(stats);

        service.searchAndMatch();

        verify(aggregatorIngestionService).ingest(linkedInSource);
        verifyNoMoreInteractions(aggregatorIngestionService);
    }
}
