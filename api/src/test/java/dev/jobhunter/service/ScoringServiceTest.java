package dev.jobhunter.service;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private MatchScoringService matchScoringService;
    @Mock private OpportunityScoringService opportunityScoringService;

    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService(
                jobPostingRepository, matchScoringService, opportunityScoringService);
    }

    private JobPosting job() {
        return JobPosting.builder().id(UUID.randomUUID()).build();
    }

    // --- scoreAllUnscored ---

    @Test
    void scoreAllUnscored_emptyPage_noop() {
        Page<JobPosting> emptyPage = new PageImpl<>(List.of());
        when(jobPostingRepository.findUnscoredActiveJobs(eq(FilterDecision.KEEP), any(Pageable.class)))
                .thenReturn(emptyPage);

        scoringService.scoreAllUnscored();

        verify(matchScoringService, never()).scoreJobs(any());
        verify(opportunityScoringService, never()).scoreJobs(any());
    }

    @Test
    void scoreAllUnscored_singlePage_scoresAllJobs() {
        var jobs = List.of(job(), job(), job());
        // First call returns jobs, second call returns empty (stops iteration)
        Page<JobPosting> page = new PageImpl<>(jobs);
        Page<JobPosting> emptyPage = new PageImpl<>(List.of());
        when(jobPostingRepository.findUnscoredActiveJobs(eq(FilterDecision.KEEP), any(Pageable.class)))
                .thenReturn(page, emptyPage);
        when(matchScoringService.scoreJobs(jobs)).thenReturn(3);
        when(opportunityScoringService.scoreJobs(jobs)).thenReturn(2);

        scoringService.scoreAllUnscored();

        verify(matchScoringService).scoreJobs(jobs);
        verify(opportunityScoringService).scoreJobs(jobs);
    }

    @Test
    void scoreAllUnscored_exceptionInScoring_caughtNotRethrown() {
        Page<JobPosting> page = new PageImpl<>(List.of(job()));
        when(jobPostingRepository.findUnscoredActiveJobs(eq(FilterDecision.KEEP), any(Pageable.class)))
                .thenReturn(page);
        when(matchScoringService.scoreJobs(any())).thenThrow(new RuntimeException("DB error"));

        // Must not throw
        scoringService.scoreAllUnscored();
    }

    // --- scoreJobsForEndpoint ---

    @Test
    void scoreJobsForEndpoint_emptyList_noop() {
        UUID endpointId = UUID.randomUUID();
        when(jobPostingRepository.findUnscoredActiveJobsByEndpoint(endpointId, FilterDecision.KEEP))
                .thenReturn(List.of());

        scoringService.scoreJobsForEndpoint(endpointId);

        verify(matchScoringService, never()).scoreJobs(any());
        verify(opportunityScoringService, never()).scoreJobs(any());
    }

    @Test
    void scoreJobsForEndpoint_withJobs_delegatesBothScorers() {
        UUID endpointId = UUID.randomUUID();
        var jobs = List.of(job(), job());
        when(jobPostingRepository.findUnscoredActiveJobsByEndpoint(endpointId, FilterDecision.KEEP))
                .thenReturn(jobs);
        when(matchScoringService.scoreJobs(jobs)).thenReturn(2);
        when(opportunityScoringService.scoreJobs(jobs)).thenReturn(1);

        scoringService.scoreJobsForEndpoint(endpointId);

        verify(matchScoringService).scoreJobs(jobs);
        verify(opportunityScoringService).scoreJobs(jobs);
    }

    // --- scoreJobsForSource ---

    @Test
    void scoreJobsForSource_emptyList_noop() {
        when(jobPostingRepository.findUnscoredActiveJobsBySource(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of());

        scoringService.scoreJobsForSource(JobSource.LINKEDIN);

        verify(matchScoringService, never()).scoreJobs(any());
        verify(opportunityScoringService, never()).scoreJobs(any());
    }

    @Test
    void scoreJobsForSource_withJobs_delegatesBothScorers() {
        var jobs = List.of(job());
        when(jobPostingRepository.findUnscoredActiveJobsBySource(JobSource.INDEED, FilterDecision.KEEP))
                .thenReturn(jobs);
        when(matchScoringService.scoreJobs(jobs)).thenReturn(1);
        when(opportunityScoringService.scoreJobs(jobs)).thenReturn(0);

        scoringService.scoreJobsForSource(JobSource.INDEED);

        verify(matchScoringService).scoreJobs(jobs);
        verify(opportunityScoringService).scoreJobs(jobs);
    }
}
