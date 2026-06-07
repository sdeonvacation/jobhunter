package dev.jobhub.service;

import dev.jobhub.config.CrawlProperties;
import dev.jobhub.extraction.ExtractionResult;
import dev.jobhub.extraction.JobExtractor;
import dev.jobhub.extraction.JobExtractorRegistry;
import dev.jobhub.extraction.RawJobData;
import dev.jobhub.extraction.SmartRecruitersExtractor;
import dev.jobhub.filter.DeduplicationFilter;
import dev.jobhub.filter.FilterResult;
import dev.jobhub.filter.LanguageFilter;
import dev.jobhub.filter.LocationFilter;
import dev.jobhub.filter.RoleRelevanceFilter;
import dev.jobhub.filter.YoeFilter;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.CrawlStatus;
import dev.jobhub.model.enums.ExtractionStatus;
import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.scheduler.ScoringScheduler;
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
    @Mock private JobExtractorRegistry extractorRegistry;
    @Mock private SmartRecruitersExtractor smartRecruitersExtractor;
    @Mock private LanguageFilter languageFilter;
    @Mock private RoleRelevanceFilter roleRelevanceFilter;
    @Mock private LocationFilter locationFilter;
    @Mock private YoeFilter yoeFilter;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private JobExtractor jobExtractor;
    @Mock private ScoringScheduler scoringScheduler;

    private CrawlService crawlService;
    private CrawlProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CrawlProperties(4, 2, 50, 30);
        crawlService = new CrawlService(
                endpointRepository, jobPostingRepository,
                extractorRegistry, smartRecruitersExtractor, languageFilter, roleRelevanceFilter,
                locationFilter, yoeFilter, deduplicationFilter, properties, scoringScheduler);
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

        var rawJob = new RawJobData("ext-1", "Engineer", "Berlin", "Java dev role",
                "https://apply.com", "{}", null, null, null, LocalDate.now());
        var result = ExtractionResult.success(List.of(rawJob), Duration.ofMillis(200));

        when(extractorRegistry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(jobExtractor));
        when(jobExtractor.canExtract(endpoint)).thenReturn(true);
        when(jobExtractor.extract(endpoint)).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(AtsType.GREENHOUSE, "ext-1"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(languageFilter.filter("Engineer", "Java dev role")).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter("Engineer")).thenReturn(FilterResult.keep());
        when(locationFilter.filter("Berlin")).thenReturn(FilterResult.keep());
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
        assertThat(saved.getSource()).isEqualTo(AtsType.GREENHOUSE);
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

        var rawJob = new RawJobData("ext-1", "Engineer", "Berlin", "desc",
                "url", "{}", null, null, null, null);
        var result = ExtractionResult.success(List.of(rawJob), Duration.ofMillis(100));

        var existingPosting = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("ext-1")
                .source(AtsType.GREENHOUSE)
                .lastCrawledAt(LocalDateTime.now().minusDays(1))
                .build();

        when(extractorRegistry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(jobExtractor));
        when(jobExtractor.canExtract(endpoint)).thenReturn(true);
        when(jobExtractor.extract(endpoint)).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(AtsType.GREENHOUSE, "ext-1"))
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

        // Extraction returns only job ext-2 (ext-1 is gone)
        var rawJob = new RawJobData("ext-2", "New Role", "Munich", "desc",
                "url", "{}", null, null, null, null);
        var result = ExtractionResult.success(List.of(rawJob), Duration.ofMillis(100));

        var staleJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("ext-1")
                .source(AtsType.GREENHOUSE)
                .isActive(true)
                .build();

        when(extractorRegistry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(jobExtractor));
        when(jobExtractor.canExtract(endpoint)).thenReturn(true);
        when(jobExtractor.extract(endpoint)).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(AtsType.GREENHOUSE, "ext-2"))
                .thenReturn(Optional.empty());
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of(staleJob));
        when(languageFilter.filter(any(), any())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(any())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(any())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(any())).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("test-fingerprint");
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(anyString(), any(FilterDecision.class))).thenReturn(Optional.empty());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        crawlService.crawlEndpoint(endpoint);

        // Verify stale job was deactivated
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

        var result = ExtractionResult.error("timeout", Duration.ofSeconds(30));

        when(extractorRegistry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(jobExtractor));
        when(jobExtractor.canExtract(endpoint)).thenReturn(true);
        when(jobExtractor.extract(endpoint)).thenReturn(result);
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        int newJobs = crawlService.crawlEndpoint(endpoint);

        assertThat(newJobs).isZero();
        verify(endpointRepository).save(endpoint);
        assertThat(endpoint.getLastCrawlStatus()).isEqualTo(CrawlStatus.ERROR);
    }

    @Test
    void crawlEndpoint_noExtractor_returnsZero() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.WORKDAY)
                .atsSlug("slug")
                .build();

        when(extractorRegistry.getExtractor(AtsType.WORKDAY)).thenReturn(Optional.empty());

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

        var rawJob = new RawJobData("ext-1", "Entwickler", "Berlin",
                "Wir suchen einen Entwickler", "url", "{}", null, null, null, null);
        var result = ExtractionResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(extractorRegistry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(jobExtractor));
        when(jobExtractor.canExtract(endpoint)).thenReturn(true);
        when(jobExtractor.extract(endpoint)).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(AtsType.GREENHOUSE, "ext-1"))
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

        when(endpointRepository.findDueForCrawl(any(LocalDateTime.class), eq(50)))
                .thenReturn(List.of(endpoint1, endpoint2));

        // First endpoint succeeds
        when(extractorRegistry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(jobExtractor));
        when(jobExtractor.canExtract(endpoint1)).thenReturn(true);
        when(jobExtractor.extract(endpoint1)).thenReturn(ExtractionResult.empty(Duration.ofMillis(50)));
        when(endpointRepository.save(endpoint1)).thenReturn(endpoint1);

        // Second endpoint throws
        JobExtractor failExtractor = mock(JobExtractor.class);
        when(extractorRegistry.getExtractor(AtsType.LEVER)).thenReturn(Optional.of(failExtractor));
        when(failExtractor.canExtract(endpoint2)).thenReturn(true);
        when(failExtractor.extract(endpoint2)).thenThrow(new RuntimeException("connection refused"));
        when(endpointRepository.save(endpoint2)).thenReturn(endpoint2);

        int[] stats = crawlService.crawlAllDueEndpoints();

        // endpoint1 succeeded, endpoint2 errored
        assertThat(stats[0]).isEqualTo(1); // endpoints crawled
        assertThat(stats[2]).isEqualTo(1); // errors
        // Both endpoints should have been attempted
        assertThat(endpoint2.getLastCrawlStatus()).isEqualTo(CrawlStatus.ERROR);
    }
}
