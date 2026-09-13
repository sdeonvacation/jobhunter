package dev.jobhunter.scheduler;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisaReaperSchedulerTest {

    private static final int MIN_DESCRIPTION_LENGTH = 500;

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private JobExecutionContext jobExecutionContext;

    private VisaReaperScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new VisaReaperScheduler(jobPostingRepository, descriptionFilterChain, MIN_DESCRIPTION_LENGTH);
    }

    @Test
    void execute_noStaleJobs_savesNothing() throws JobExecutionException {
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of());

        scheduler.execute(jobExecutionContext);

        verify(jobPostingRepository, never()).saveAll(any());
    }

    @Test
    void execute_staleJobs_deactivatesAllWithCorrectFields() throws JobExecutionException {
        JobPosting job1 = pendingJob("job-1");
        JobPosting job2 = pendingJob("job-2");

        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job1, job2));

        scheduler.execute(jobExecutionContext);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobPosting>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobPostingRepository).saveAll(captor.capture());

        List<JobPosting> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        for (JobPosting job : saved) {
            assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
            assertThat(job.isActive()).isFalse();
            assertThat(job.getFilterReason()).isEqualTo("visa: pending timed out after 24h");
        }
    }

    @Test
    void execute_usesTodayAsCutoff() throws JobExecutionException {
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of());

        scheduler.execute(jobExecutionContext);

        verify(jobPostingRepository).findActivePendingVisaJobsDiscoveredBefore(eq(LocalDate.now()));
    }

    @Test
    void execute_exceptionFromRepository_doesNotPropagate() {
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));
    }

    @Test
    void execute_singleStaleJob_deactivatedAndSaved() throws JobExecutionException {
        JobPosting job = pendingJob("single");
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job));

        scheduler.execute(jobExecutionContext);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(job.isActive()).isFalse();
        assertThat(job.getFilterReason()).isEqualTo("visa: pending timed out after 24h");
        verify(jobPostingRepository).saveAll(List.of(job));
    }

    // --- resolve-before-reap ---

    @Test
    @DisplayName("job with a decidable description is resolved instead of reaped")
    void execute_decidableJob_resolvedNotReaped() throws JobExecutionException {
        JobPosting job = pendingJobWithDescription("resolvable");
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job));
        doAnswer(inv -> {
            inv.getArgument(0, JobPosting.class).setVisaSponsorship(VisaSponsorship.LIKELY);
            return null;
        }).when(descriptionFilterChain).refilter(job);

        scheduler.execute(jobExecutionContext);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.LIKELY);
        assertThat(job.isActive()).isTrue();
        assertThat(job.getFilterReason()).isNull();
        verify(jobPostingRepository).saveAll(List.of(job));
    }

    @Test
    @DisplayName("job resolved to a rejection keeps the refilter reason and its own (already applied) decision")
    void execute_resolvedToSkip_keepsRefilterReason() throws JobExecutionException {
        JobPosting job = pendingJobWithDescription("resolved-skip");
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job));
        doAnswer(inv -> {
            JobPosting target = inv.getArgument(0);
            target.setLanguageFilter(FilterDecision.SKIP);
            target.setFilterReason("non-English JD (German)");
            target.setVisaSponsorship(VisaSponsorship.UNKNOWN);
            return null;
        }).when(descriptionFilterChain).refilter(job);

        scheduler.execute(jobExecutionContext);

        assertThat(job.getFilterReason()).isEqualTo("non-English JD (German)");
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    @Test
    @DisplayName("residual PENDING after refilter (unresolvable) is reaped")
    void execute_residualPendingAfterRefilter_isReaped() throws JobExecutionException {
        JobPosting job = pendingJobWithDescription("stuck");
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job));
        // refilter leaves PENDING set
        doAnswer(inv -> null).when(descriptionFilterChain).refilter(job);

        scheduler.execute(jobExecutionContext);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(job.isActive()).isFalse();
        assertThat(job.getFilterReason()).isEqualTo("visa: pending timed out after 24h");
    }

    @Test
    @DisplayName("short description (below threshold) is reaped without refiltering")
    void execute_shortDescription_isReapedWithoutRefilter() throws JobExecutionException {
        JobPosting job = pendingJob("short");
        job.setDescription("too short");
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job));

        scheduler.execute(jobExecutionContext);

        assertThat(job.isActive()).isFalse();
        verify(descriptionFilterChain, never()).refilter(any());
    }

    @Test
    @DisplayName("an existing rejection reason is preserved when reaping")
    void execute_existingFilterReason_preservedOnReap() throws JobExecutionException {
        JobPosting job = pendingJob("has-reason");
        job.setFilterReason("superseded by endpoint");
        when(jobPostingRepository.findActivePendingVisaJobsDiscoveredBefore(any(LocalDate.class)))
                .thenReturn(List.of(job));

        scheduler.execute(jobExecutionContext);

        assertThat(job.isActive()).isFalse();
        assertThat(job.getFilterReason()).isEqualTo("superseded by endpoint");
    }

    // --- Helpers ---

    private JobPosting pendingJob(String externalId) {
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId(externalId)
                .languageFilter(FilterDecision.KEEP)
                .visaSponsorship(VisaSponsorship.PENDING)
                .isActive(true)
                .discoveredDate(LocalDate.now().minusDays(2))
                .build();
    }

    private JobPosting pendingJobWithDescription(String externalId) {
        JobPosting job = pendingJob(externalId);
        job.setDescription("x".repeat(MIN_DESCRIPTION_LENGTH + 100));
        return job;
    }
}
