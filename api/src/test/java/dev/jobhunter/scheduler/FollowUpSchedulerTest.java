package dev.jobhunter.scheduler;

import dev.jobhunter.dto.FollowUpDto;
import dev.jobhunter.service.FollowUpCadenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowUpSchedulerTest {

    @Mock private FollowUpCadenceService followUpCadenceService;
    @Mock private JobExecutionContext jobExecutionContext;

    @InjectMocks private FollowUpScheduler scheduler;

    @Test
    void execute_noOverdue_logsZero() throws JobExecutionException {
        when(followUpCadenceService.getOverdue()).thenReturn(List.of());

        scheduler.execute(jobExecutionContext);

        verify(followUpCadenceService).getOverdue();
    }

    @Test
    void execute_overdueFound_callsService() throws JobExecutionException {
        FollowUpDto dto = new FollowUpDto(
                UUID.randomUUID(), UUID.randomUUID(), "Test Job", "Test Co",
                LocalDate.now().minusDays(1), null, 1, "OVERDUE", null, LocalDateTime.now());
        when(followUpCadenceService.getOverdue()).thenReturn(List.of(dto));

        scheduler.execute(jobExecutionContext);

        verify(followUpCadenceService).getOverdue();
    }

    @Test
    void execute_serviceThrows_doesNotPropagate() {
        when(followUpCadenceService.getOverdue()).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));
    }
}
