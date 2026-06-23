package dev.jobhunter.service;

import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import dev.jobhunter.filter.DeduplicationFilter;
import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.filter.JobFilterChain;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.people.crawl.PostCrawlPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests the in-crawl description backfill path:
 * existing job found without description → rawJob supplies description → descriptionFilterChain.refilter() called.
 */
@ExtendWith(MockitoExtension.class)
class CrawlServiceBackfillYoeTest {

    @Mock private CareerEndpointRepository endpointRepository;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private StrategyRegistry strategyRegistry;
    @Mock private JobFilterChain jobFilterChain;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private ScoringService scoringService;
    @Mock private FetchStrategy fetchStrategy;
    @Mock private PostCrawlPipeline postCrawlPipeline;

    private CrawlService crawlService;

    @BeforeEach
    void setUp() {
        crawlService = new CrawlService(
                endpointRepository, jobPostingRepository, strategyRegistry,
                jobFilterChain, deduplicationFilter, descriptionFilterChain,
                List.of(), List.of(), scoringService, postCrawlPipeline);
    }

    private CareerEndpoint endpoint(AtsType atsType) {
        return CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(atsType)
                .atsSlug("company-x")
                .build();
    }

    @Test
    void existingJob_nullDescription_backfillsAndCallsRefilter() {
        var endpoint = endpoint(AtsType.GREENHOUSE);
        var existingJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("gh-1")
                .source(JobSource.GREENHOUSE)
                .languageFilter(FilterDecision.KEEP)
                .build();

        var rawJob = new RawAggregatorJob("gh-1", "Senior Engineer", null, "Berlin",
                "We need 8+ years of experience in backend development",
                "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "gh-1"))
                .thenReturn(Optional.of(existingJob));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        assertThat(existingJob.getDescription())
                .isEqualTo("We need 8+ years of experience in backend development");
        verify(descriptionFilterChain).refilter(existingJob);
    }

    @Test
    void existingJob_withDescription_doesNotOverwriteOrRefilter() {
        var endpoint = endpoint(AtsType.GREENHOUSE);
        var existingJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("gh-2")
                .source(JobSource.GREENHOUSE)
                .languageFilter(FilterDecision.KEEP)
                .description("Existing description")
                .build();

        var rawJob = new RawAggregatorJob("gh-2", "Engineer", null, "Berlin",
                "New description should be ignored", "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "gh-2"))
                .thenReturn(Optional.of(existingJob));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        assertThat(existingJob.getDescription()).isEqualTo("Existing description");
        verify(descriptionFilterChain, never()).refilter(any());
    }

    @Test
    void existingJob_rawJobNullDescription_doesNotRefilter() {
        var endpoint = endpoint(AtsType.GREENHOUSE);
        var existingJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("gh-3")
                .source(JobSource.GREENHOUSE)
                .languageFilter(FilterDecision.KEEP)
                .build();

        // rawJob has no description
        var rawJob = new RawAggregatorJob("gh-3", "Engineer", null, "Berlin",
                null, "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "gh-3"))
                .thenReturn(Optional.of(existingJob));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        assertThat(existingJob.getDescription()).isNull();
        verify(descriptionFilterChain, never()).refilter(any());
    }

    @Test
    void existingJob_refilterMutatesFilterDecision_savedCorrectly() {
        var endpoint = endpoint(AtsType.GREENHOUSE);
        var existingJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("gh-4")
                .source(JobSource.GREENHOUSE)
                .languageFilter(FilterDecision.KEEP)
                .build();

        var rawJob = new RawAggregatorJob("gh-4", "Entwickler", null, "Berlin",
                "Wir suchen fließend Deutsch C1", "url", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        // refilter() mutates the job (marks it as SKIP due to language)
        doAnswer(inv -> {
            JobPosting job = inv.getArgument(0);
            job.setLanguageFilter(FilterDecision.SKIP);
            job.setFilterReason("German JD");
            return null;
        }).when(descriptionFilterChain).refilter(any(JobPosting.class));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "gh-4"))
                .thenReturn(Optional.of(existingJob));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        assertThat(existingJob.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(existingJob.getFilterReason()).isEqualTo("German JD");
        verify(jobPostingRepository).save(existingJob);
    }

    @Test
    void existingJob_applyUrlBackfilled_whenNull() {
        var endpoint = endpoint(AtsType.GREENHOUSE);
        var existingJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("gh-5")
                .source(JobSource.GREENHOUSE)
                .languageFilter(FilterDecision.KEEP)
                .build();

        var rawJob = new RawAggregatorJob("gh-5", "Engineer", null, "Berlin",
                null, "https://new-apply.com/5", null, null, null, null, "{}");
        var result = FetchResult.success(List.of(rawJob), Duration.ofMillis(100));

        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(fetchStrategy));
        when(fetchStrategy.fetch(any(FetchContext.class))).thenReturn(result);
        when(jobPostingRepository.findBySourceAndExternalId(JobSource.GREENHOUSE, "gh-5"))
                .thenReturn(Optional.of(existingJob));
        when(jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId())).thenReturn(List.of());
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));
        when(endpointRepository.save(any(CareerEndpoint.class))).thenAnswer(i -> i.getArgument(0));
        when(jobPostingRepository.bulkDeactivateByEndpointExcluding(any(), any(), any())).thenReturn(0);

        crawlService.crawlEndpoint(endpoint);

        assertThat(existingJob.getApplyUrl()).isEqualTo("https://new-apply.com/5");
    }
}
