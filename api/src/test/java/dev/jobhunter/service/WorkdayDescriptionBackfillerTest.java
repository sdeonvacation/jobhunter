package dev.jobhunter.service;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.ats.WorkdayStrategy;
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
class WorkdayDescriptionBackfillerTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private WorkdayStrategy workdayStrategy;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private MatchScoringService matchScoringService;

    private WorkdayDescriptionBackfiller backfiller;

    @BeforeEach
    void setUp() {
        backfiller = new WorkdayDescriptionBackfiller(
                jobPostingRepository, workdayStrategy,
                descriptionFilterChain, matchScoringService);
    }

    private CareerEndpoint endpoint() {
        return CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(AtsType.WORKDAY)
                .atsSlug("company-wd")
                .build();
    }

    @Test
    void backfill_noEligibleJobs_doesNothing() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of());

        backfiller.backfill();

        verifyNoInteractions(workdayStrategy, descriptionFilterChain, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_jobWithNullEndpoint_skipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.WORKDAY)
                .endpoint(null)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(workdayStrategy, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_strategyReturnsNull_notSaved() {
        var ep = endpoint();
        var job = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("wd-1").source(JobSource.WORKDAY)
                .endpoint(ep).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of(job));
        when(workdayStrategy.fetchDescription(ep, "wd-1")).thenReturn(null);

        backfiller.backfill();

        verify(jobPostingRepository, never()).save(any());
        verifyNoInteractions(matchScoringService);
    }

    @Test
    void backfill_strategyReturnsBlank_notSaved() {
        var ep = endpoint();
        var job = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("wd-2").source(JobSource.WORKDAY)
                .endpoint(ep).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of(job));
        when(workdayStrategy.fetchDescription(ep, "wd-2")).thenReturn("   ");

        backfiller.backfill();

        verify(jobPostingRepository, never()).save(any());
        verifyNoInteractions(matchScoringService);
    }

    @Test
    void backfill_descriptionFetched_savesAndRescores() {
        var ep = endpoint();
        var job = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("wd-3").source(JobSource.WORKDAY)
                .endpoint(ep).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of(job));
        when(workdayStrategy.fetchDescription(ep, "wd-3")).thenReturn("Full description");
        when(jobPostingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        backfiller.backfill();

        assertThat(job.getDescription()).isEqualTo("Full description");
        verify(descriptionFilterChain).refilter(job);
        verify(jobPostingRepository).save(job);
        verify(matchScoringService).rescoreJob(job);
    }

    @Test
    void backfill_doesNotCallRescoreJobDirectly() {
        // rescoreJob must NOT be called inside doBackfill() — only via base class after return
        var ep = endpoint();
        var job = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("wd-4").source(JobSource.WORKDAY)
                .endpoint(ep).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of(job));
        when(workdayStrategy.fetchDescription(ep, "wd-4")).thenReturn("Description");
        when(jobPostingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        backfiller.backfill();

        // Exactly once — from base class template method, not from doBackfill() directly
        verify(matchScoringService, times(1)).rescoreJob(job);
    }

    @Test
    void backfill_multipleJobs_onlyFilledRescored() {
        var ep = endpoint();
        var jobFilled = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("wd-5").source(JobSource.WORKDAY)
                .endpoint(ep).build();
        var jobEmpty = JobPosting.builder()
                .id(UUID.randomUUID()).externalId("wd-6").source(JobSource.WORKDAY)
                .endpoint(ep).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndShortDescription(
                eq(JobSource.WORKDAY), eq(FilterDecision.KEEP), anyInt()))
                .thenReturn(List.of(jobFilled, jobEmpty));
        when(workdayStrategy.fetchDescription(ep, "wd-5")).thenReturn("Filled desc");
        when(workdayStrategy.fetchDescription(ep, "wd-6")).thenReturn(null);
        when(jobPostingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        backfiller.backfill();

        verify(matchScoringService).rescoreJob(jobFilled);
        verify(matchScoringService, never()).rescoreJob(jobEmpty);
    }
}
