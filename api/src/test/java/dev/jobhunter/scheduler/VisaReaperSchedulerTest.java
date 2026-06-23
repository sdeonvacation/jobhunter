package dev.jobhunter.scheduler;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisaReaperSchedulerTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobExecutionContext jobExecutionContext;
    @InjectMocks private VisaReaperScheduler scheduler;

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

    // --- Helper ---

    private JobPosting pendingJob(String externalId) {
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId(externalId)
                .visaSponsorship(VisaSponsorship.PENDING)
                .isActive(true)
                .discoveredDate(LocalDate.now().minusDays(2))
                .build();
    }
}
