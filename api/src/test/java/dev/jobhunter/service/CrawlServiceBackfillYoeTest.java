package dev.jobhunter.service;

import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.strategy.ats.SmartRecruitersStrategy;
import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.LocationFilter;
import dev.jobhunter.filter.RoleRelevanceFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrawlServiceBackfillYoeTest {

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
    void backfill_yoeExceedsThreshold_marksSkip() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("company-x")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-yoe-1")
                .title("Senior Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        String description = "We need 8+ years of experience in backend development";

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("company-x", "sr-yoe-1"))
                .thenReturn(description);
        when(languageFilter.filter("Senior Engineer", description))
                .thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(description)).thenReturn(8);
        when(yoeFilter.filter(8)).thenReturn(FilterResult.skip("Requires 8 YOE (max: 5)"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(1); // filled
        assertThat(result[1]).isEqualTo(1); // filtered by YOE

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getDescription()).isEqualTo(description);
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(saved.getFilterReason()).isEqualTo("Requires 8 YOE (max: 5)");
        assertThat(saved.getRequiredYoe()).isEqualTo(8);
    }

    @Test
    void backfill_yoeBelowThreshold_keepsJob() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("company-y")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-yoe-2")
                .title("Backend Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        String description = "3+ years of experience with Java and Spring Boot";

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("company-y", "sr-yoe-2"))
                .thenReturn(description);
        when(languageFilter.filter("Backend Engineer", description))
                .thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(description)).thenReturn(3);
        when(yoeFilter.filter(3)).thenReturn(FilterResult.keep());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(1); // filled
        assertThat(result[1]).isZero();     // not filtered

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(saved.getRequiredYoe()).isEqualTo(3);
    }

    @Test
    void backfill_noYoeMentioned_keepsJob() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("company-z")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-yoe-3")
                .title("Software Developer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        String description = "Join our team building microservices with Kotlin";

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("company-z", "sr-yoe-3"))
                .thenReturn(description);
        when(languageFilter.filter("Software Developer", description))
                .thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(description)).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(1);
        assertThat(result[1]).isZero();

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(saved.getRequiredYoe()).isNull();
    }

    @Test
    void backfill_languageSkip_doesNotRunYoeFilter() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("firma")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-yoe-4")
                .title("Entwickler")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        String description = "Wir suchen einen Entwickler mit 8 Jahren Erfahrung";

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("firma", "sr-yoe-4"))
                .thenReturn(description);
        when(languageFilter.filter("Entwickler", description))
                .thenReturn(FilterResult.skip("German JD"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(1); // filled
        assertThat(result[1]).isEqualTo(1); // filtered by language

        // YOE filter should NOT be called when language already skipped
        verify(yoeFilter, never()).extractYoe(any());
        verify(yoeFilter, never()).filter(any());
    }

    @Test
    void backfill_mixedYoeAndLanguageFiltering_countsCorrectly() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("multi-co")
                .build();
        var keepJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("keep-1")
                .title("Junior Dev")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();
        var yoeSkipJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("yoe-skip-1")
                .title("Staff Engineer")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();
        var langSkipJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("lang-skip-1")
                .title("Architekt")
                .source(JobSource.SMARTRECRUITERS)
                .languageFilter(FilterDecision.KEEP)
                .endpoint(endpoint)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(keepJob, yoeSkipJob, langSkipJob));

        when(smartRecruitersStrategy.fetchDescription("multi-co", "keep-1"))
                .thenReturn("2 years Java experience");
        when(smartRecruitersStrategy.fetchDescription("multi-co", "yoe-skip-1"))
                .thenReturn("10+ years distributed systems");
        when(smartRecruitersStrategy.fetchDescription("multi-co", "lang-skip-1"))
                .thenReturn("Fließend Deutsch erforderlich");

        when(languageFilter.filter("Junior Dev", "2 years Java experience"))
                .thenReturn(FilterResult.keep());
        when(languageFilter.filter("Staff Engineer", "10+ years distributed systems"))
                .thenReturn(FilterResult.keep());
        when(languageFilter.filter("Architekt", "Fließend Deutsch erforderlich"))
                .thenReturn(FilterResult.skip("German required"));

        when(yoeFilter.extractYoe("2 years Java experience")).thenReturn(2);
        when(yoeFilter.filter(2)).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe("10+ years distributed systems")).thenReturn(10);
        when(yoeFilter.filter(10)).thenReturn(FilterResult.skip("Requires 10 YOE (max: 5)"));

        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        int[] result = crawlService.backfillSmartRecruitersDescriptions();

        assertThat(result[0]).isEqualTo(3); // all 3 filled
        assertThat(result[1]).isEqualTo(2); // 1 language + 1 YOE

        var captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository, times(3)).save(captor.capture());
        var saved = captor.getAllValues();

        var savedKeep = saved.stream()
                .filter(j -> "keep-1".equals(j.getExternalId())).findFirst().orElseThrow();
        assertThat(savedKeep.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(savedKeep.getRequiredYoe()).isEqualTo(2);

        var savedYoeSkip = saved.stream()
                .filter(j -> "yoe-skip-1".equals(j.getExternalId())).findFirst().orElseThrow();
        assertThat(savedYoeSkip.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(savedYoeSkip.getFilterReason()).isEqualTo("Requires 10 YOE (max: 5)");
        assertThat(savedYoeSkip.getRequiredYoe()).isEqualTo(10);

        var savedLangSkip = saved.stream()
                .filter(j -> "lang-skip-1".equals(j.getExternalId())).findFirst().orElseThrow();
        assertThat(savedLangSkip.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(savedLangSkip.getFilterReason()).isEqualTo("German required");
    }
}
