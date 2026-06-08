package dev.jobhunter.ingestion;

import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.LocationFilter;
import dev.jobhunter.filter.RoleRelevanceFilter;
import dev.jobhunter.filter.YoeFilter;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
    @Mock private LanguageFilter languageFilter;
    @Mock private RoleRelevanceFilter roleRelevanceFilter;
    @Mock private LocationFilter locationFilter;
    @Mock private YoeFilter yoeFilter;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private FetchStrategy fetchStrategy;
    @Mock private PostIngestionEnricher enricher1;
    @Mock private PostIngestionEnricher enricher2;

    private AggregatorIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AggregatorIngestionServiceImpl(
                jobPostingRepository, companyRepository, aggregatorRunRepository,
                languageFilter, roleRelevanceFilter, locationFilter,
                yoeFilter, deduplicationFilter,
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
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.LINKEDIN, "li-001")).thenReturn(Optional.empty());
        when(deduplicationFilter.generateFingerprint("Backend Dev", "TestCo", "Berlin")).thenReturn("fp");
        when(jobPostingRepository.findAtsJobByFingerprint("fp", JobSource.aggregators())).thenReturn(Optional.empty());
        when(languageFilter.filter(anyString(), any())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(any())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
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
        // Duplicate - already exists
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.ARBEITNOW, "arb-001"))
                .thenReturn(Optional.of(JobPosting.builder().build()));
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
