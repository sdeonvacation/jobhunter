package dev.jobhunter.ingestion;

import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.FilterChainResult;
import dev.jobhunter.filter.JobFilterChain;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.CompanyStatus;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.AggregatorRunRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.source.SourceConfig;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests that AggregatorIngestionServiceImpl correctly invokes PostIngestionEnrichers.
 */
@ExtendWith(MockitoExtension.class)
class PostIngestionEnricherHookTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private JobFilterChain jobFilterChain;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private FetchStrategy fetchStrategy;
    @Mock private PostIngestionEnricher enricher1;
    @Mock private PostIngestionEnricher enricher2;

    private AggregatorIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AggregatorIngestionServiceImpl(
                jobPostingRepository, companyRepository, aggregatorRunRepository,
                jobFilterChain, deduplicationFilter,
                List.of(enricher1, enricher2));
    }

    private SourceConfig createSourceConfig(JobSource source, DiscoverySource discovery) {
        return new SourceConfig() {
            @Override public String name() { return "test-source"; }
            @Override public JobSource sourceType() { return source; }
            @Override public DiscoverySource discoverySource() { return discovery; }
            @Override public FetchStrategy strategy() { return fetchStrategy; }
            @Override public FetchContext buildContext() { return FetchContext.forSearch(List.of("java"), List.of("Berlin"), 100, 5, Map.of()); }
            @Override public int frequencyHours() { return 4; }
            @Override public boolean isEnabled() { return true; }
        };
    }

    @Test
    void ingest_withCreatedJobs_invokesAllEnrichers() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        var job = new RawAggregatorJob("li-001", "Backend Dev", "TestCo", "Berlin",
                null, "https://linkedin.com/jobs/li-001", LocalDate.now(),
                null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.LINKEDIN)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(any())).thenReturn(new HashSet<>());
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(companyRepository.findByNormalizedName("testco")).thenReturn(Optional.of(
                Company.builder().id(UUID.randomUUID()).name("TestCo").normalizedName("testco")
                        .status(CompanyStatus.DISCOVERED).isActive(true).build()));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        service.ingest(sourceConfig);

        verify(enricher1).enrich(JobSource.LINKEDIN, 1);
        verify(enricher2).enrich(JobSource.LINKEDIN, 1);
    }

    @Test
    void ingest_noJobsCreated_stillInvokesEnrichersWithZero() {
        var sourceConfig = createSourceConfig(JobSource.ARBEITNOW, DiscoverySource.ARBEITNOW);
        var job = new RawAggregatorJob("arb-001", "Backend Dev", "TestCo", "Berlin",
                "desc", "https://arbeitnow.com/jobs/arb-001", LocalDate.now(),
                null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        // Pre-loaded set already contains the externalId — job is a duplicate
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.ARBEITNOW))
                .thenReturn(new HashSet<>(Set.of("arb-001")));
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(any())).thenReturn(new HashSet<>());
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        service.ingest(sourceConfig);

        verify(enricher1).enrich(JobSource.ARBEITNOW, 0);
        verify(enricher2).enrich(JobSource.ARBEITNOW, 0);
    }

    @Test
    void ingest_fetchFails_enrichersNotCalled() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        when(fetchStrategy.fetch(any())).thenThrow(new RuntimeException("Network error"));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        service.ingest(sourceConfig);

        verify(enricher1, never()).enrich(any(), anyInt());
        verify(enricher2, never()).enrich(any(), anyInt());
    }

    @Test
    void ingest_emptyResult_enrichersNotCalled() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        when(fetchStrategy.fetch(any())).thenReturn(FetchResult.empty(Duration.ofMillis(50)));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        service.ingest(sourceConfig);

        verify(enricher1, never()).enrich(any(), anyInt());
        verify(enricher2, never()).enrich(any(), anyInt());
    }
}
