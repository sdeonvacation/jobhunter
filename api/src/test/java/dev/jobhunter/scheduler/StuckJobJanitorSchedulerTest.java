package dev.jobhunter.scheduler;

import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StuckJobJanitorSchedulerTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobExecutionContext jobExecutionContext;
    @InjectMocks private StuckJobJanitorScheduler scheduler;

    @Test
    void execute_callsSoftHideWith30DayCutoff() throws JobExecutionException {
        when(jobPostingRepository.softHideStuckJobsOlderThan(any(), any(LocalDateTime.class)))
                .thenReturn(7);

        scheduler.execute(jobExecutionContext);

        ArgumentCaptor<String> prefixCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobPostingRepository).softHideStuckJobsOlderThan(prefixCaptor.capture(), cutoffCaptor.capture());

        assertThat(prefixCaptor.getValue()).isEqualTo("url-dead-stuck-%");

        LocalDateTime expectedCutoff = LocalDateTime.now().minus(Duration.ofDays(30));
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(2, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void execute_handlesRepositoryException_logsError() {
        when(jobPostingRepository.softHideStuckJobsOlderThan(eq("url-dead-stuck-%"), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));
    }
}
