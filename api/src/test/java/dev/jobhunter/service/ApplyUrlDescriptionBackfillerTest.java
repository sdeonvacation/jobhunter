package dev.jobhunter.service;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplyUrlDescriptionBackfillerTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private MatchScoringService matchScoringService;

    private ApplyUrlDescriptionBackfiller backfiller;

    @BeforeEach
    void setUp() {
        backfiller = new ApplyUrlDescriptionBackfiller(
                jobPostingRepository, descriptionFilterChain,
                matchScoringService, new ObjectMapper());
    }

    @Test
    void backfill_noEligibleJobs_doesNothing() {
        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of());

        backfiller.backfill();

        verifyNoInteractions(descriptionFilterChain, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_smartRecruitersJobs_allSkipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.SMARTRECRUITERS)
                .applyUrl("https://smartrecruiters.com/job/1")
                .build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(descriptionFilterChain, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_workdayJobs_allSkipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.WORKDAY)
                .applyUrl("https://workday.com/job/1")
                .build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(descriptionFilterChain, matchScoringService);
    }

    @Test
    void backfill_workdayProtectedJobs_allSkipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.WORKDAY_PROTECTED)
                .applyUrl("https://workday.com/protected/1")
                .build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(descriptionFilterChain, matchScoringService);
    }

    @Test
    void backfill_jobWithNullApplyUrl_skipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.GREENHOUSE)
                .applyUrl(null)
                .build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(descriptionFilterChain, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_jobWithBlankApplyUrl_skipped() {
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.GREENHOUSE)
                .applyUrl("   ")
                .build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(job));

        backfiller.backfill();

        verifyNoInteractions(descriptionFilterChain, matchScoringService);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void backfill_fetchThrowsException_continuesAndNoRescore() {
        // Invalid URL causes Jsoup to throw — should be caught and not crash the loop
        var job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.GREENHOUSE)
                .applyUrl("not-a-valid-url")
                .build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(job));

        // Should not throw
        backfiller.backfill();

        verify(jobPostingRepository, never()).save(any());
        verifyNoInteractions(matchScoringService);
    }

    @Test
    void backfill_mixedSources_onlyEligibleAttempted() {
        var srJob = JobPosting.builder()
                .id(UUID.randomUUID()).source(JobSource.SMARTRECRUITERS)
                .applyUrl("https://sr.com/1").build();
        var ghJob = JobPosting.builder()
                .id(UUID.randomUUID()).source(JobSource.GREENHOUSE)
                .applyUrl("not-a-valid-url").build();

        when(jobPostingRepository.findActiveKeepJobsWithApplyUrlButNoDescription(
                eq(FilterDecision.KEEP), any(PageRequest.class)))
                .thenReturn(List.of(srJob, ghJob));

        backfiller.backfill();

        // SR job skipped, GH job attempted (fetch fails), neither saved
        verify(jobPostingRepository, never()).save(any());
        verifyNoInteractions(matchScoringService);
    }
}
