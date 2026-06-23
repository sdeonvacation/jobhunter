package dev.jobhunter.ingestion;

import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.FilterChainResult;
import dev.jobhunter.filter.JobFilterChain;
import dev.jobhunter.model.AggregatorRun;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.CompanyStatus;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.FilterDecision;
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
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AggregatorIngestionServiceImplTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AggregatorRunRepository aggregatorRunRepository;
    @Mock private JobFilterChain jobFilterChain;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private FetchStrategy fetchStrategy;

    private AggregatorIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AggregatorIngestionServiceImpl(
                jobPostingRepository, companyRepository, aggregatorRunRepository,
                jobFilterChain, deduplicationFilter,
                List.of());
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

    private RawAggregatorJob createJob(String id, String title, String company) {
        return new RawAggregatorJob(id, title, company, "Berlin", "Java developer role",
                "https://apply.example.com/" + id, LocalDate.now(),
                BigDecimal.valueOf(70000), BigDecimal.valueOf(90000), "EUR", "{}");
    }

    @Test
    void ingest_newJob_createsJobPosting() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = createJob("ext-1", "Backend Engineer", "Acme Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(deduplicationFilter.generateFingerprint("Backend Engineer", "Acme Corp", "Berlin"))
                .thenReturn("fingerprint-abc");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));

        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp")
                .normalizedName("acme corp").status(CompanyStatus.DISCOVERED).isActive(true).build();
        when(companyRepository.findByNormalizedName("acme corp")).thenReturn(Optional.of(company));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.created()).isEqualTo(1);
        assertThat(stats.fetched()).isEqualTo(1);
        assertThat(stats.duplicates()).isZero();
        assertThat(stats.filtered()).isZero();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        JobPosting saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(JobSource.BERLIN_STARTUP_JOBS);
        assertThat(saved.getExternalId()).isEqualTo("ext-1");
        assertThat(saved.getTitle()).isEqualTo("Backend Engineer");
        assertThat(saved.getCompany()).isEqualTo(company);
        assertThat(saved.getFingerprint()).isEqualTo("fingerprint-abc");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void ingest_duplicateBySourceAndExternalId_skipped() {
        var sourceConfig = createSourceConfig(JobSource.ARBEITNOW, DiscoverySource.ARBEITNOW);
        var job = createJob("ext-1", "Backend Engineer", "Acme Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        // Pre-loaded set already contains the externalId — no per-job DB query needed
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.ARBEITNOW))
                .thenReturn(new HashSet<>(Set.of("ext-1")));
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.duplicates()).isEqualTo(1);
        assertThat(stats.created()).isZero();
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        // Confirm no per-job findBySourceAndExternalId calls
        verify(jobPostingRepository, never()).findBySourceAndExternalId(any(), anyString());
    }

    @Test
    void ingest_duplicateDetectedViaPreloadedSet_skipped() {
        var sourceConfig = createSourceConfig(JobSource.ARBEITNOW, DiscoverySource.ARBEITNOW);
        var job = createJob("ext-already-known", "Backend Engineer", "Acme Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.ARBEITNOW))
                .thenReturn(new HashSet<>(Set.of("ext-already-known")));
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.duplicates()).isEqualTo(1);
        assertThat(stats.created()).isZero();
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        verify(jobPostingRepository, never()).findBySourceAndExternalId(any(), anyString());
    }

    @Test
    void ingest_atsJobWithSameFingerprint_enrichesExisting() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        var job = createJob("li-123", "Backend Engineer", "Acme Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.LINKEDIN)).thenReturn(new HashSet<>());
        // Fingerprint in pre-loaded set — triggers the ATS lookup
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators()))
                .thenReturn(new HashSet<>(Set.of("fingerprint-xyz")));
        when(deduplicationFilter.generateFingerprint("Backend Engineer", "Acme Corp", "Berlin"))
                .thenReturn("fingerprint-xyz");

        JobPosting existingAtsJob = JobPosting.builder()
                .id(UUID.randomUUID()).source(JobSource.GREENHOUSE)
                .externalId("gh-456").title("Backend Engineer")
                .externalLinks(new java.util.HashMap<>())
                .build();
        when(jobPostingRepository.findAtsJobByFingerprint("fingerprint-xyz", JobSource.aggregators()))
                .thenReturn(Optional.of(existingAtsJob));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.enriched()).isEqualTo(1);
        assertThat(stats.created()).isZero();
        assertThat(existingAtsJob.getExternalLinks()).containsEntry("test-source", "https://apply.example.com/li-123");
    }

    @Test
    void ingest_filteredByLanguage_notCreated() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = createJob("ext-1", "Entwickler", "German Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.skip("German title"));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.filtered()).isEqualTo(1);
        assertThat(stats.created()).isZero();
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
    }

    @Test
    void ingest_filteredByRole_notCreated() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = createJob("ext-1", "Product Manager", "Acme Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.skip("Not engineering role"));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.filtered()).isEqualTo(1);
        assertThat(stats.created()).isZero();
    }

    @Test
    void ingest_fetchThrows_returnsErrorStats() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        when(fetchStrategy.fetch(any())).thenThrow(new RuntimeException("Network error"));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.fetched()).isZero();
        assertThat(stats.created()).isZero();
    }

    @Test
    void ingest_emptyResult_returnsZeroStats() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        when(fetchStrategy.fetch(any())).thenReturn(FetchResult.empty(Duration.ofMillis(50)));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.fetched()).isZero();
        assertThat(stats.created()).isZero();
        assertThat(stats.errors()).isZero();
    }

    @Test
    void ingest_unknownCompany_createsNewCompany() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = createJob("ext-1", "Backend Engineer", "New Startup GmbH");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("fp");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(companyRepository.findByNormalizedName("new startup gmbh")).thenReturn(Optional.empty());

        Company newCompany = Company.builder().id(UUID.randomUUID()).name("New Startup GmbH")
                .normalizedName("new startup gmbh").status(CompanyStatus.DISCOVERED).isActive(true).build();
        when(companyRepository.save(any(Company.class))).thenReturn(newCompany);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.created()).isEqualTo(1);

        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(companyCaptor.capture());
        Company savedCompany = companyCaptor.getValue();
        assertThat(savedCompany.getName()).isEqualTo("New Startup GmbH");
        assertThat(savedCompany.getNormalizedName()).isEqualTo("new startup gmbh");
        assertThat(savedCompany.getStatus()).isEqualTo(CompanyStatus.DISCOVERED);
        assertThat(savedCompany.getDiscoveredVia()).isEqualTo(DiscoverySource.BERLIN_STARTUP_JOBS);
    }

    @Test
    void ingest_updatesAggregatorRun() {
        var sourceConfig = createSourceConfig(JobSource.ARBEITNOW, DiscoverySource.ARBEITNOW);
        var job = createJob("ext-1", "Backend Engineer", "Acme Corp");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.ARBEITNOW)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("fp");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, 2));
        when(companyRepository.findByNormalizedName("acme corp")).thenReturn(
                Optional.of(Company.builder().id(UUID.randomUUID()).name("Acme Corp").normalizedName("acme corp").isActive(true).status(CompanyStatus.ACTIVE).build()));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        AggregatorRun existingRun = AggregatorRun.builder().sourceName("test-source").build();
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.of(existingRun));
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        service.ingest(sourceConfig);

        ArgumentCaptor<AggregatorRun> runCaptor = ArgumentCaptor.forClass(AggregatorRun.class);
        verify(aggregatorRunRepository).save(runCaptor.capture());
        AggregatorRun savedRun = runCaptor.getValue();
        assertThat(savedRun.getSourceName()).isEqualTo("test-source");
        assertThat(savedRun.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(savedRun.getJobsCreated()).isEqualTo(1);
        assertThat(savedRun.getJobsFetched()).isEqualTo(1);
    }

    @Test
    void ingest_nullCompanyName_usesUnknown() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = new RawAggregatorJob("ext-1", "Backend Engineer", null, "Berlin",
                "Java dev", "https://apply.example.com/ext-1", LocalDate.now(),
                null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(deduplicationFilter.generateFingerprint("Backend Engineer", "", "Berlin")).thenReturn("fp");
        // ATS fingerprint matching is skipped when companyName is null (Bug #3 fix)
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));

        Company unknownCompany = Company.builder().id(UUID.randomUUID()).name("Unknown")
                .normalizedName("unknown").status(CompanyStatus.DISCOVERED).isActive(true).build();
        when(companyRepository.findByNormalizedName("unknown")).thenReturn(Optional.of(unknownCompany));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.created()).isEqualTo(1);
        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getCompany()).isEqualTo(unknownCompany);
        // Verify ATS fingerprint lookup was NOT called (null company skips it)
        verify(jobPostingRepository, never()).findAtsJobByFingerprint(anyString(), any());
    }

    @Test
    void ingest_blankCompanyName_skipsAtsFingerprintMatching() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = new RawAggregatorJob("ext-1", "Backend Engineer", "  ", "Berlin",
                "Java dev", "https://apply.example.com/ext-1", LocalDate.now(),
                null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(deduplicationFilter.generateFingerprint("Backend Engineer", "  ", "Berlin")).thenReturn("fp");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));

        Company unknownCompany = Company.builder().id(UUID.randomUUID()).name("Unknown")
                .normalizedName("unknown").status(CompanyStatus.DISCOVERED).isActive(true).build();
        when(companyRepository.findByNormalizedName("unknown")).thenReturn(Optional.of(unknownCompany));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.created()).isEqualTo(1);
        verify(jobPostingRepository, never()).findAtsJobByFingerprint(anyString(), any());
    }

    @Test
    void ingest_errorResult_setsErrorsToOne() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        when(fetchStrategy.fetch(any())).thenReturn(FetchResult.error("Connection timeout", Duration.ofMillis(200)));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.fetched()).isZero();
        assertThat(stats.errors()).isEqualTo(1);

        ArgumentCaptor<AggregatorRun> captor = ArgumentCaptor.forClass(AggregatorRun.class);
        verify(aggregatorRunRepository).save(captor.capture());
        AggregatorRun saved = captor.getValue();
        assertThat(saved.getLastStatus()).isEqualTo("ERROR");
        assertThat(saved.getErrors()).isEqualTo(1);
    }

    @Test
    void ingest_emptyResult_setsErrorsToZero() {
        var sourceConfig = createSourceConfig(JobSource.LINKEDIN, DiscoverySource.LINKEDIN);
        when(fetchStrategy.fetch(any())).thenReturn(FetchResult.empty(Duration.ofMillis(50)));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.errors()).isZero();

        ArgumentCaptor<AggregatorRun> captor = ArgumentCaptor.forClass(AggregatorRun.class);
        verify(aggregatorRunRepository).save(captor.capture());
        AggregatorRun saved = captor.getValue();
        assertThat(saved.getLastStatus()).isEqualTo("EMPTY");
        assertThat(saved.getErrors()).isZero();
    }

    @Test
    void ingest_batchPreload_calledOnceForMultipleJobs() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var jobs = List.of(
                createJob("ext-1", "Backend Engineer", "Acme Corp"),
                createJob("ext-2", "Frontend Engineer", "Beta Corp")
        );
        var fetchResult = FetchResult.success(jobs, Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        // Distinct fingerprints per job — avoids knownFingerprints cross-hit after first save
        when(deduplicationFilter.generateFingerprint("Backend Engineer", "Acme Corp", "Berlin")).thenReturn("fp-1");
        when(deduplicationFilter.generateFingerprint("Frontend Engineer", "Beta Corp", "Berlin")).thenReturn("fp-2");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp")
                .normalizedName("acme corp").isActive(true).status(CompanyStatus.ACTIVE).build();
        when(companyRepository.findByNormalizedName(anyString())).thenReturn(Optional.of(company));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.created()).isEqualTo(2);
        // Batch pre-load called exactly once, not once per job
        verify(jobPostingRepository, times(1)).findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS);
        verify(jobPostingRepository, times(1)).findAtsFingerprintsExcludingSources(JobSource.aggregators());
        // No per-job findBySourceAndExternalId calls
        verify(jobPostingRepository, never()).findBySourceAndExternalId(any(), anyString());
    }

    @Test
    void ingest_withinBatchDuplicate_secondJobSkippedViaLocalSet() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        // Two jobs with the same externalId in one batch
        var jobs = List.of(
                createJob("ext-dup", "Backend Engineer", "Acme Corp"),
                createJob("ext-dup", "Backend Engineer", "Acme Corp")
        );
        var fetchResult = FetchResult.success(jobs, Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(jobPostingRepository.findAtsFingerprintsExcludingSources(JobSource.aggregators())).thenReturn(new HashSet<>());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("fp");
        when(jobFilterChain.apply(any(), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp")
                .normalizedName("acme corp").isActive(true).status(CompanyStatus.ACTIVE).build();
        when(companyRepository.findByNormalizedName("acme corp")).thenReturn(Optional.of(company));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        // Only the first job is created; the second is caught by the updated local set
        assertThat(stats.created()).isEqualTo(1);
        assertThat(stats.duplicates()).isEqualTo(1);
    }

    @Test
    void ingest_nullExternalId_skippedAndCountedAsError() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = new RawAggregatorJob(null, "Backend Engineer", "Acme Corp", "Berlin",
                "Java dev", "https://apply.example.com/ext-1", LocalDate.now(),
                null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.created()).isZero();
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        // Fingerprint must not be computed — null guard fires first
        verify(deduplicationFilter, never()).generateFingerprint(anyString(), anyString(), anyString());
    }

    @Test
    void ingest_blankExternalId_skippedAndCountedAsError() {
        var sourceConfig = createSourceConfig(JobSource.BERLIN_STARTUP_JOBS, DiscoverySource.BERLIN_STARTUP_JOBS);
        var job = new RawAggregatorJob("   ", "Backend Engineer", "Acme Corp", "Berlin",
                "Java dev", "https://apply.example.com/ext-1", LocalDate.now(),
                null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(job), Duration.ofMillis(100));

        when(fetchStrategy.fetch(any())).thenReturn(fetchResult);
        when(jobPostingRepository.findExternalIdsBySourceAsSet(JobSource.BERLIN_STARTUP_JOBS)).thenReturn(new HashSet<>());
        when(aggregatorRunRepository.findBySourceName("test-source")).thenReturn(Optional.empty());
        when(aggregatorRunRepository.save(any(AggregatorRun.class))).thenAnswer(i -> i.getArgument(0));

        IngestionStats stats = service.ingest(sourceConfig);

        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.created()).isZero();
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        verify(deduplicationFilter, never()).generateFingerprint(anyString(), anyString(), anyString());
    }
}
