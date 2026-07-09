package dev.jobhunter.service;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.ats.SmartRecruitersStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartRecruitersDescriptionBackfillerTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private SmartRecruitersStrategy smartRecruitersStrategy;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private MatchScoringService matchScoringService;

    private SmartRecruitersDescriptionBackfiller backfiller;

    @BeforeEach
    void setUp() {
        backfiller = new SmartRecruitersDescriptionBackfiller(
                jobPostingRepository, smartRecruitersStrategy,
                descriptionFilterChain, matchScoringService);
    }

    @Test
    void backfill_noEligibleJobs_doesNothing() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of());

        backfiller.backfill();

        verifyNoInteractions(smartRecruitersStrategy, descriptionFilterChain, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_jobWithNullSlug_skipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.SMARTRECRUITERS)
                .endpoint(null)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(smartRecruitersStrategy, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_strategyReturnsNull_jobNotSaved() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("company-a")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-1")
                .source(JobSource.SMARTRECRUITERS)
                .endpoint(endpoint)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("company-a", "sr-1")).thenReturn(null);

        backfiller.backfill();

        verify(jobPostingRepository, never()).save(any());
        verifyNoInteractions(matchScoringService);
    }

    @Test
    void backfill_descriptionFetched_savesAndRescores() {
        var endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsSlug("company-b")
                .build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("sr-2")
                .source(JobSource.SMARTRECRUITERS)
                .endpoint(endpoint)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("company-b", "sr-2"))
                .thenReturn("Full job description text");
        when(jobPostingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        backfiller.backfill();

        assertThat(job.getDescription()).isEqualTo("Full job description text");
        verify(descriptionFilterChain).refilter(job);
        verify(jobPostingRepository).save(job);
        verify(matchScoringService).rescoreJob(job);
    }

    @Test
    void backfill_multipleJobs_onlyFilledRescored() {
        var endpoint = CareerEndpoint.builder().id(UUID.randomUUID()).atsSlug("co").build();
        var jobFilled = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("sr-3").source(JobSource.SMARTRECRUITERS)
                .endpoint(endpoint).build();
        var jobMissing = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("sr-4").source(JobSource.SMARTRECRUITERS)
                .endpoint(endpoint).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(jobFilled, jobMissing));
        when(smartRecruitersStrategy.fetchDescription("co", "sr-3")).thenReturn("Description A");
        when(smartRecruitersStrategy.fetchDescription("co", "sr-4")).thenReturn(null);
        when(jobPostingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        backfiller.backfill();

        verify(matchScoringService).rescoreJob(jobFilled);
        verify(matchScoringService, never()).rescoreJob(jobMissing);
    }

    @Test
    void backfill_doesNotCallDeleteByJobId() {
        // Verify the old MatchScoreRepository.deleteByJobId pattern is absent
        var endpoint = CareerEndpoint.builder().id(UUID.randomUUID()).atsSlug("co").build();
        var job = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("sr-5").source(JobSource.SMARTRECRUITERS)
                .endpoint(endpoint).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(
                JobSource.SMARTRECRUITERS, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(smartRecruitersStrategy.fetchDescription("co", "sr-5")).thenReturn("desc");
        when(jobPostingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // If MatchScoreRepository were injected and called, this test would need it as a mock.
        // Its absence in the constructor confirms it's not used.
        backfiller.backfill();

        // rescoreJob on base class handles score lifecycle
        verify(matchScoringService).rescoreJob(job);
    }
}
