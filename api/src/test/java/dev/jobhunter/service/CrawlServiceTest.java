package dev.jobhunter.service;

import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.filter.FilterChainResult;
import dev.jobhunter.filter.JobFilterChain;
import dev.jobhunter.filter.RawJobInput;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import dev.jobhunter.people.crawl.PostCrawlPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrawlServiceTest {

    @Mock private CareerEndpointRepository endpointRepository;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private StrategyRegistry strategyRegistry;
    @Mock private JobFilterChain jobFilterChain;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private ScoringService scoringService;
    @Mock private FetchStrategy fetchStrategy;
    @Mock private PostCrawlPipeline postCrawlPipeline;
    @Mock private MatchScoreRepository matchScoreRepository;

    private CrawlService crawlService;

    @BeforeEach
    void setUp() {
        crawlService = new CrawlService(
                endpointRepository, jobPostingRepository, strategyRegistry,
                jobFilterChain, deduplicationFilter, descriptionFilterChain,
                List.of(), List.of(), scoringService, postCrawlPipeline, matchScoreRepository);
        // @Value field not set by Spring in unit tests; set manually
        ReflectionTestUtils.setField(crawlService, "crawlConcurrency", 10);
    }

    @Test
    void crawlEndpoint_newJobs_savesWithFilter() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-1", "Engineer", null, "Berlin", "Java dev role",
                "https://apply.com", LocalDate.now(), null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(200));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("test-fingerprint");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        int newJobs = crawlService.crawlEndpoint(endpoint);

        assertThat(newJobs).isEqualTo(1);

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getExternalId()).isEqualTo("ext-1");
        assertThat(saved.getTitle()).isEqualTo("Engineer");
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(saved.getSource()).isEqualTo(JobSource.GREENHOUSE);
    }

    @Test
    void crawlEndpoint_existingJob_updatesLastCrawled() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-1", "Engineer", null, "Berlin", "desc",
                "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        var existingPosting = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("ext-1")
                .source(JobSource.GREENHOUSE)
                .lastCrawledAt(LocalDateTime.now().minusDays(1))
                .build();

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.of(existingPosting));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId()))
                .thenReturn(List.of(existingPosting));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        int newJobs = crawlService.crawlEndpoint(endpoint);

        assertThat(newJobs).isZero();
        verify(jobFilterChain, never()).apply(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void crawlEndpoint_existingJob_backfillsDescription_callsRefilter() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        // rawJob has a description that the existing posting lacks
        var rawJob = new RawAggregatorJob("ext-1", "Engineer", null, "Berlin",
                "Exciting Java role with Spring Boot", "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        var existingPosting = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("ext-1")
                .source(JobSource.GREENHOUSE)
                .languageFilter(FilterDecision.KEEP)
                .build();

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.of(existingPosting));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        // descriptionFilterChain.refilter() must be called when description is backfilled
        verify(descriptionFilterChain).refilter(existingPosting);
        assertThat(existingPosting.getDescription()).isEqualTo("Exciting Java role with Spring Boot");
    }

    @Test
    void crawlEndpoint_missingJobs_bulkDeactivated() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-2", "New Role", null, "Munich", "desc",
                "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-2"))
                .thenReturn(Optional.empty());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(1);

        crawlService.crawlEndpoint(endpoint);

        // bulkDeactivate called with endpoint ID and seen IDs containing ext-2
        var idCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(jobPostingRepository).bulkDeactivateByEndpointExcluding(
                eq(endpoint.getId()), idCaptor.capture(), any(LocalDateTime.class));
        assertThat(idCaptor.getValue()).contains("ext-2");
    }

    @Test
    void crawlEndpoint_extractionError_marksEndpoint() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var result = FetchResult.error("timeout", Duration.ofSeconds(30));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int newJobs = crawlService.crawlEndpoint(endpoint);

        assertThat(newJobs).isZero();
        verify(endpointRepository).save(endpoint);
        assertThat(endpoint.getLastCrawlStatus()).isEqualTo(CrawlStatus.ERROR);
    }

    @Test
    void crawlEndpoint_noStrategy_returnsZero() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.WORKDAY)
                .atsSlug("slug")
                .build();

        when(strategyRegistry.getStrategy(AtsType.WORKDAY)).thenReturn(Optional.empty());

        int result = crawlService.crawlEndpoint(endpoint);
        assertThat(result).isZero();
    }

    @Test
    void crawlEndpoint_noStrategy_setsSkippedAndUpdatesLastCrawledAt() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .build();

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.empty());
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int result = crawlService.crawlEndpoint(endpoint);

        assertThat(result).isZero();
        assertThat(endpoint.getLastCrawlStatus()).isEqualTo(CrawlStatus.SKIPPED);
        assertThat(endpoint.getLastCrawledAt()).isNotNull();
        verify(endpointRepository).save(endpoint);
    }

    @Test
    void crawlEndpoint_filteredJob_savedWithSkipDecision() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-1", "Entwickler", null, "Berlin",
                "Wir suchen einen Entwickler", "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp-de");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.skip("German JD"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(saved.getFilterReason()).isEqualTo("German JD");
        // postCrawlPipeline must NOT be called for SKIP jobs
        verify(postCrawlPipeline, never()).run(any(), any(), any());
    }

    @Test
    void crawlAllDueEndpoints_isolatesFailures() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint1 = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("good")
                .company(company)
                .build();
        var endpoint2 = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.LEVER)
                .atsSlug("bad")
                .company(company)
                .build();

        when(endpointRepository.findAllActiveNonCustom())
                .thenReturn(List.of(endpoint1, endpoint2));

        FetchStrategy goodStrategy = mock(FetchStrategy.class);
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(goodStrategy));
        when(goodStrategy.fetch(any(FetchContext.class))).thenReturn(FetchResult.empty(Duration.ofMillis(50)));
        when(endpointRepository.save(endpoint1)).thenReturn(endpoint1);

        FetchStrategy failStrategy = mock(FetchStrategy.class);
        when(strategyRegistry.getStrategy(AtsType.LEVER)).thenReturn(Optional.of(failStrategy));
        when(failStrategy.fetch(any(FetchContext.class))).thenThrow(new RuntimeException("connection refused"));
        when(endpointRepository.save(endpoint2)).thenReturn(endpoint2);

        int[] stats = crawlService.crawlAllDueEndpoints();

        assertThat(stats[0]).isEqualTo(1);
        assertThat(stats[2]).isEqualTo(1);
        assertThat(endpoint2.getLastCrawlStatus()).isEqualTo(CrawlStatus.ERROR);
    }

    @Test
    void crawlEndpoint_keepJob_postCrawlPipelineInvoked() {
        var company = Company.builder().id(UUID.randomUUID()).name("AcmeCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("acme")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-5", "Backend Dev", null, "Berlin", "Good role",
                "https://apply.com/5", LocalDate.now(), null, null, null, "{\"k\":\"v\"}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(50));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-5"))
                .thenReturn(Optional.empty());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp-5");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, 3));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        verify(postCrawlPipeline).run(any(JobPosting.class), anyString(), any());
    }

    @Test
    void crawlEndpoint_filterChainResultFieldsStoredOnPosting() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-9", "Engineer", null, "Amsterdam", "desc",
                "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(50));
        var chainResult = FilterChainResult.keep(
                dev.jobhunter.model.enums.VisaSponsorship.LIKELY, 4);

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-9"))
                .thenReturn(Optional.empty());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp-9");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(chainResult);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getRequiredYoe()).isEqualTo(4);
        assertThat(saved.getVisaSponsorship()).isEqualTo(dev.jobhunter.model.enums.VisaSponsorship.LIKELY);
        assertThat(saved.getFingerprint()).isEqualTo("fp-9");
    }

    // ── crawlAllDueEndpoints() ────────────────────────────────────────────────

    @Test
    void crawlAllDueEndpoints_noEndpoints_returnsZeros() {
        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of());

        int[] stats = crawlService.crawlAllDueEndpoints();

        assertThat(stats).containsExactly(0, 0, 0);
        verifyNoInteractions(strategyRegistry);
    }

    @Test
    void crawlAllDueEndpoints_singleEndpointNewJob_scoredAfterCrawl() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-x", "Engineer", null, "Berlin", "desc",
                "url", LocalDate.now(), null, null, null, "{}");
        var fetchResult = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of(endpoint));
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(fetchResult);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-x"))
                .thenReturn(Optional.empty());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp-x");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        int[] stats = crawlService.crawlAllDueEndpoints();

        assertThat(stats[0]).isEqualTo(1); // endpointsCrawled
        assertThat(stats[1]).isEqualTo(1); // totalJobs
        assertThat(stats[2]).isEqualTo(0); // errors
        verify(scoringService).scoreJobsForEndpoint(endpoint.getId());
    }

    @Test
    void crawlAllDueEndpoints_endpointWithZeroJobs_noScoringTriggered() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var fetchResult = FetchResult.empty(Duration.ofMillis(50));

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of(endpoint));
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(fetchResult);
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int[] stats = crawlService.crawlAllDueEndpoints();

        assertThat(stats[0]).isEqualTo(1); // endpointsCrawled
        assertThat(stats[1]).isEqualTo(0); // no new jobs
        assertThat(stats[2]).isEqualTo(0); // no errors
        verify(scoringService, never()).scoreJobsForEndpoint(any());
    }

    @Test
    void crawlAllDueEndpoints_fetchThrows_countedAsError_pipelineContinues() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of(endpoint));
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenThrow(new RuntimeException("timeout"));
        lenient().when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int[] stats = crawlService.crawlAllDueEndpoints();

        assertThat(stats[0]).isEqualTo(0); // not counted as crawled
        assertThat(stats[2]).isEqualTo(1); // error counted
        verify(scoringService, never()).scoreJobsForEndpoint(any());
    }

    @Test
    void crawlAllDueEndpoints_multipleEndpoints_allProcessed() {
        var company = Company.builder().id(UUID.randomUUID()).name("Co").build();
        var ep1 = CareerEndpoint.builder().id(UUID.randomUUID()).atsType(AtsType.GREENHOUSE)
                .atsSlug("co1").company(company).build();
        var ep2 = CareerEndpoint.builder().id(UUID.randomUUID()).atsType(AtsType.GREENHOUSE)
                .atsSlug("co2").company(company).build();

        var emptyResult = FetchResult.empty(Duration.ofMillis(30));

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of(ep1, ep2));
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(emptyResult);
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int[] stats = crawlService.crawlAllDueEndpoints();

        assertThat(stats[0]).isEqualTo(2); // both endpoints crawled
        assertThat(stats[1]).isEqualTo(0);
        assertThat(stats[2]).isEqualTo(0);
    }

    @Test
    void crawlAllDueEndpoints_withBackfillPostProcessor_processCalledAfterCrawl() {
        var backfillProcessor = mock(dev.jobhunter.ingestion.BackfillPostProcessor.class);
        var serviceWithProcessor = new CrawlService(
                endpointRepository, jobPostingRepository, strategyRegistry,
                jobFilterChain, deduplicationFilter, descriptionFilterChain,
                List.of(), List.of(backfillProcessor), scoringService, postCrawlPipeline);
        ReflectionTestUtils.setField(serviceWithProcessor, "crawlConcurrency", 10);

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of());

        serviceWithProcessor.crawlAllDueEndpoints();

        verify(backfillProcessor).process();
    }

    // ── Fix 2: SUCCESS + empty jobs should not deactivate existing postings ──

    @Test
    void crawlEndpoint_successStatusWithEmptyJobList_setsEmptyStatus_noDeactivation() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        // SUCCESS status but with an empty jobs list
        var result = FetchResult.success(List.of(), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int newJobs = crawlService.crawlEndpoint(endpoint);

        assertThat(newJobs).isZero();
        assertThat(endpoint.getLastCrawlStatus()).isEqualTo(CrawlStatus.EMPTY);
        // deactivation logic must NOT run — no existing postings wiped
        verify(jobPostingRepository, never()).findByEndpointIdAndIsActiveTrue(any());
        verify(jobPostingRepository, never()).bulkDeactivateByEndpointExcluding(any(), any(), any());
    }

    // ── Fix 3: markEndpointError increments consecutiveErrors ────────────────

    @Test
    void crawlAllDueEndpoints_fetchThrows_incrementsConsecutiveErrors() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build(); // consecutiveErrors defaults to 0

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of(endpoint));
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenThrow(new RuntimeException("timeout"));
        lenient().when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        crawlService.crawlAllDueEndpoints();

        assertThat(endpoint.getConsecutiveErrors()).isEqualTo(1);
        assertThat(endpoint.getLastCrawlStatus()).isEqualTo(CrawlStatus.ERROR);
    }

    @Test
    void crawlAllDueEndpoints_tenConsecutiveFetchErrors_autoDeactivatesEndpoint() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .consecutiveErrors(9) // one more will hit the threshold
                .build();             // isActive defaults to true

        when(endpointRepository.findAllActiveNonCustom()).thenReturn(List.of(endpoint));
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenThrow(new RuntimeException("timeout"));
        lenient().when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        crawlService.crawlAllDueEndpoints();

        assertThat(endpoint.getConsecutiveErrors()).isEqualTo(10);
        assertThat(endpoint.isActive()).isFalse();
    }

    // --- Aggregator demotion ---

    @Test
    void crawlEndpoint_newKeepJob_demotesExistingAggregatorJob() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-1", "Engineer", null, "Berlin", "Java dev role",
                "https://apply.com", LocalDate.now(), null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(200));

        var aggregatorJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.LINKEDIN)
                .fingerprint("test-fingerprint")
                .languageFilter(FilterDecision.KEEP)
                .applyUrl("https://linkedin.com/jobs/view/123")
                .build();

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("test-fingerprint");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);
        when(jobPostingRepository.findByFingerprintAndLanguageFilterAndSourceIn(
                eq("test-fingerprint"), eq(FilterDecision.KEEP), any()))
                .thenReturn(List.of(aggregatorJob));

        crawlService.crawlEndpoint(endpoint);

        // Aggregator job must be demoted to SKIP
        assertThat(aggregatorJob.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(aggregatorJob.getFilterReason()).isEqualTo("superseded by endpoint");
        // Match score for aggregator must be deleted
        verify(matchScoreRepository).deleteByJobId(aggregatorJob.getId());
    }

    @Test
    void crawlEndpoint_newSkipJob_doesNotDemoteAggregators() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-1", "Manager", null, "Berlin", "Management role",
                "https://apply.com", LocalDate.now(), null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(200));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("skip-fingerprint");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.skip("manager role"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        // Aggregator demotion query must NOT be called for SKIP jobs
        verify(jobPostingRepository, never())
                .findByFingerprintAndLanguageFilterAndSourceIn(anyString(), any(), any());
        verify(matchScoreRepository, never()).deleteByJobId(any());
    }

    @Test
    void crawlEndpoint_newKeepJob_copiesAggregatorApplyUrlToExternalLinks() {
        var company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .company(company)
                .build();

        var rawJob = new RawAggregatorJob("ext-1", "Engineer", null, "Berlin", "Java role",
                "https://apply.com", LocalDate.now(), null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(200));

        var aggregatorJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.INDEED)
                .fingerprint("fp")
                .languageFilter(FilterDecision.KEEP)
                .applyUrl("https://indeed.com/jobs/456")
                .build();

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobFilterChain.apply(any(RawJobInput.class), anyBoolean(), anyBoolean()))
                .thenReturn(FilterChainResult.keep(null, null));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);
        when(jobPostingRepository.findByFingerprintAndLanguageFilterAndSourceIn(
                eq("fp"), eq(FilterDecision.KEEP), any()))
                .thenReturn(List.of(aggregatorJob));

        // Capture all saved JobPostings
        var captor = ArgumentCaptor.forClass(JobPosting.class);
        when(jobPostingRepository.save(captor.capture())).thenAnswer(i -> i.getArgument(0));

        crawlService.crawlEndpoint(endpoint);

        // Find the endpoint job among saved postings (not the demoted aggregator)
        var endpointJobSaves = captor.getAllValues().stream()
                .filter(j -> j.getSource() == JobSource.GREENHOUSE)
                .toList();
        assertThat(endpointJobSaves).isNotEmpty();
        var endpointJob = endpointJobSaves.get(endpointJobSaves.size() - 1); // last save has externalLinks
        assertThat(endpointJob.getExternalLinks()).containsEntry("INDEED", "https://indeed.com/jobs/456");
    }
}

