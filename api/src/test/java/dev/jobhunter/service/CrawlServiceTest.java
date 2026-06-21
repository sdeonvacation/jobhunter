package dev.jobhunter.service;

import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import dev.jobhunter.strategy.ats.SmartRecruitersStrategy;
import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.LocationFilter;
import dev.jobhunter.filter.RoleRelevanceFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.CrawlStatus;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.scheduler.ScoringScheduler;
import dev.jobhunter.people.crawl.PostCrawlPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrawlServiceTest {

    @Mock private CareerEndpointRepository endpointRepository;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private StrategyRegistry strategyRegistry;
    @Mock private SmartRecruitersStrategy smartRecruitersStrategy;
    @Mock private LanguageFilter languageFilter;
    @Mock private RoleRelevanceFilter roleRelevanceFilter;
    @Mock private LocationFilter locationFilter;
    @Mock private YoeFilter yoeFilter;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private VisaSponsorshipFilter visaSponsorshipFilter;
    @Mock private FetchStrategy fetchStrategy;
    @Mock private ScoringScheduler scoringScheduler;
    @Mock private PostCrawlPipeline postCrawlPipeline;

    private CrawlService crawlService;

    @BeforeEach
    void setUp() {
        crawlService = new CrawlService(endpointRepository, jobPostingRepository,
                strategyRegistry, smartRecruitersStrategy, languageFilter, roleRelevanceFilter,
                locationFilter, yoeFilter, deduplicationFilter, visaSponsorshipFilter,
                scoringScheduler, postCrawlPipeline);
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
        when(languageFilter.filter("Engineer", "Java dev role")).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter("Engineer")).thenReturn(FilterResult.keep());
        when(locationFilter.filter("Berlin")).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), eq(false))).thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(any())).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("test-fingerprint");
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(anyString(), any(FilterDecision.class))).thenReturn(Optional.empty());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

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
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of(existingPosting));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int newJobs = crawlService.crawlEndpoint(endpoint);

        assertThat(newJobs).isZero();
        verify(languageFilter, never()).filter(any(), any());
    }

    @Test
    void crawlEndpoint_missingJobs_deactivated() {
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

        var staleJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("ext-1")
                .source(JobSource.GREENHOUSE)
                .isActive(true)
                .build();

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "ext-2"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of(staleJob));
        when(languageFilter.filter(any(), any())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(any())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(any())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), eq(false))).thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(any())).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("test-fingerprint");
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(anyString(), any(FilterDecision.class))).thenReturn(Optional.empty());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        crawlService.crawlEndpoint(endpoint);

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository, atLeast(2)).save(captor.capture());
        var savedJobs = captor.getAllValues();
        var deactivated = savedJobs.stream()
                .filter(j -> "ext-1".equals(j.getExternalId()))
                .findFirst().orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getDeactivatedAt()).isNotNull();
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
    void crawlEndpoint_germanJob_savedWithSkipDecision() {
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
        when(languageFilter.filter("Entwickler", "Wir suchen einen Entwickler"))
                .thenReturn(FilterResult.skip("German JD"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        crawlService.crawlEndpoint(endpoint);

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(saved.getFilterReason()).isEqualTo("German JD");
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

    // --- Backfill + language re-filter tests ---

    @Test
    void backfillDescriptions_noJobs_returnsZeros() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of());

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isZero();
        assertThat(result[1]).isZero();
        verify(smartRecruitersStrategy, never()).fetchDescription(any(), any());
    }

    @Test
    void backfillDescriptions_englishJob_keepsFilterStatus() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("company-slug")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-123")
                .title("Software Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("company-slug", "sr-123"))
                .thenReturn("We are looking for a Java engineer");
        when(languageFilter.filter("Software Engineer", "We are looking for a Java engineer"))
                .thenReturn(FilterResult.keep());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(1); // filled
        assertThat(result[1]).isZero();     // none filtered

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getDescription()).isEqualTo("We are looking for a Java engineer");
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(saved.getFilterReason()).isNull();
    }

    @Test
    void backfillDescriptions_germanJob_marksSkip() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("firma-slug")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-456")
                .title("Entwickler")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        String germanDesc = "Wir suchen einen erfahrenen Softwareentwickler mit Java-Kenntnissen";

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("firma-slug", "sr-456"))
                .thenReturn(germanDesc);
        when(languageFilter.filter("Entwickler", germanDesc))
                .thenReturn(FilterResult.skip("German JD"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(1); // filled
        assertThat(result[1]).isEqualTo(1); // filtered

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getDescription()).isEqualTo(germanDesc);
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(saved.getFilterReason()).isEqualTo("German JD");
    }

    @Test
    void backfillDescriptions_nullSlug_skipsJob() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-789")
                .title("Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(null)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isZero();
        assertThat(result[1]).isZero();
        verify(smartRecruitersStrategy, never()).fetchDescription(any(), any());
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
    }

    @Test
    void backfillDescriptions_fetchReturnsNull_doesNotSave() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("slug")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-000")
                .title("Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("slug", "sr-000"))
                .thenReturn(null);

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isZero();
        assertThat(result[1]).isZero();
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        verify(languageFilter, never()).filter(any(), any());
    }

    @Test
    void backfillDescriptions_mixedJobs_countsCorrectly() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("mixed-co")
                .build();
        var englishJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("en-1")
                .title("Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();
        var germanJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("de-1")
                .title("Entwickler")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(englishJob, germanJob));
        when(smartRecruitersStrategy.fetchDescription("mixed-co", "en-1"))
                .thenReturn("English description");
        when(smartRecruitersStrategy.fetchDescription("mixed-co", "de-1"))
                .thenReturn("Deutsche Beschreibung mit fließend Deutsch C1 erforderlich");
        when(languageFilter.filter("Engineer", "English description"))
                .thenReturn(FilterResult.keep());
        when(languageFilter.filter("Entwickler", "Deutsche Beschreibung mit fließend Deutsch C1 erforderlich"))
                .thenReturn(FilterResult.skip("German C1/C2 required"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(2); // both filled
        assertThat(result[1]).isEqualTo(1); // one filtered

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository, times(2)).save(captor.capture());
        var saved = captor.getAllValues();

        var savedEnglish = saved.stream()
                .filter(j -> "en-1".equals(j.getExternalId())).findFirst().orElseThrow();
        assertThat(savedEnglish.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);

        var savedGerman = saved.stream()
                .filter(j -> "de-1".equals(j.getExternalId())).findFirst().orElseThrow();
        assertThat(savedGerman.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(savedGerman.getFilterReason()).isEqualTo("German C1/C2 required");
    }
}
