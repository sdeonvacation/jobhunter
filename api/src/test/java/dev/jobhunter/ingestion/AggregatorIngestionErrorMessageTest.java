package dev.jobhunter.ingestion;

import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.LocationFilter;
import dev.jobhunter.filter.RoleRelevanceFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.source.SourceConfig;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AggregatorIngestionErrorMessageTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private LanguageFilter languageFilter;
    @Mock private RoleRelevanceFilter roleRelevanceFilter;
    @Mock private LocationFilter locationFilter;
    @Mock private YoeFilter yoeFilter;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private VisaSponsorshipFilter visaSponsorshipFilter;
    @Mock private FetchStrategy fetchStrategy;

    private AggregatorIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AggregatorIngestionServiceImpl(
                jobPostingRepository, companyRepository, aggregatorRunRepository,
                languageFilter, roleRelevanceFilter, locationFilter,
                yoeFilter, deduplicationFilter, visaSponsorshipFilter,
                List.of());
    }

    private SourceConfig createSourceConfig() {
        return new SourceConfig() {
            @Override public String name() { return "test-source"; }
            @Override public JobSource sourceType() { return JobSource.LINKEDIN; }
            @Override public DiscoverySource discoverySource() { return DiscoverySource.LINKEDIN; }
            @Override public FetchStrategy strategy() { return fetchStrategy; }
            @Override public FetchContext buildContext() { return FetchContext.forSearch(List.of("java"), List.of("Berlin"), 100, 5, Map.of()); }
            @Override public int frequencyHours() { return 4; }
            @Override public boolean isEnabled() { return true; }
        };
    }

    @Test
    @DisplayName("Should persist errorMessage from FetchResult.error() to AggregatorRun")
    void shouldPersistErrorMessageFromFetchResult() {
        FetchResult errorResult = FetchResult.error("All searches failed (2): Connection timeout", Duration.ofSeconds(5));
        when(fetchStrategy.fetch(any())).thenReturn(errorResult);
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(createSourceConfig());

        ArgumentCaptor<AggregatorRun> captor = ArgumentCaptor.forClass(AggregatorRun.class);
        verify(aggregatorRunRepository).save(captor.capture());

        AggregatorRun savedRun = captor.getValue();
        assertThat(savedRun.getLastStatus()).isEqualTo("ERROR");
        assertThat(savedRun.getErrorMessage()).isEqualTo("All searches failed (2): Connection timeout");
        assertThat(savedRun.getErrors()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should persist error message when strategy throws exception")
    void shouldPersistErrorMessageOnException() {
        when(fetchStrategy.fetch(any())).thenThrow(new RuntimeException("MCP server unreachable"));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(createSourceConfig());

        ArgumentCaptor<AggregatorRun> captor = ArgumentCaptor.forClass(AggregatorRun.class);
        verify(aggregatorRunRepository).save(captor.capture());

        AggregatorRun savedRun = captor.getValue();
        assertThat(savedRun.getLastStatus()).isEqualTo("ERROR");
        assertThat(savedRun.getErrorMessage()).isEqualTo("MCP server unreachable");
    }

    @Test
    @DisplayName("Should clear errorMessage on successful run")
    void shouldClearErrorMessageOnSuccess() {
        FetchResult emptyResult = FetchResult.empty(Duration.ofSeconds(1));
        when(fetchStrategy.fetch(any())).thenReturn(emptyResult);

        AggregatorRun existingRun = AggregatorRun.builder()
                .sourceName("test-source")
                .errorMessage("previous error")
                .build();
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.of(existingRun));
        when(aggregatorRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(createSourceConfig());

        ArgumentCaptor<AggregatorRun> captor = ArgumentCaptor.forClass(AggregatorRun.class);
        verify(aggregatorRunRepository).save(captor.capture());

        AggregatorRun savedRun = captor.getValue();
        assertThat(savedRun.getLastStatus()).isEqualTo("EMPTY");
        assertThat(savedRun.getErrorMessage()).isNull();
    }
}
