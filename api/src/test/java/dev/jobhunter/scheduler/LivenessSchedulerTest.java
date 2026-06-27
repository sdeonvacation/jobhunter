package dev.jobhunter.scheduler;

import dev.jobhunter.dto.LivenessResultDto;
import dev.jobhunter.service.LivenessCheckService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LivenessSchedulerTest {

    @Mock private LivenessCheckService livenessCheckService;
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    @Mock private JobExecutionContext jobExecutionContext;

    @InjectMocks private LivenessScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(eq("cutoff"), any(LocalDateTime.class))).thenReturn(query);
    }

    @Test
    void execute_noJobsNeedingCheck_doesNotCallService() throws JobExecutionException {
        when(query.getResultList()).thenReturn(List.of());

        scheduler.execute(jobExecutionContext);

        verifyNoInteractions(livenessCheckService);
    }

    @Test
    void execute_multipleJobs_checksEach() throws JobExecutionException {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(query.getResultList()).thenReturn(List.of(id1, id2));

        LivenessResultDto activeResult = new LivenessResultDto(id1, "ACTIVE", LocalDateTime.now(), "http://x", null);
        LivenessResultDto expiredResult = new LivenessResultDto(id2, "EXPIRED", LocalDateTime.now(), "http://y", "page gone");
        when(livenessCheckService.checkLiveness(id1)).thenReturn(activeResult);
        when(livenessCheckService.checkLiveness(id2)).thenReturn(expiredResult);

        scheduler.execute(jobExecutionContext);

        verify(livenessCheckService).checkLiveness(id1);
        verify(livenessCheckService).checkLiveness(id2);
    }

    @Test
    void execute_oneJobThrows_continuesWithRest() throws JobExecutionException {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(query.getResultList()).thenReturn(List.of(id1, id2));

        when(livenessCheckService.checkLiveness(id1)).thenThrow(new RuntimeException("timeout"));
        LivenessResultDto result = new LivenessResultDto(id2, "ACTIVE", LocalDateTime.now(), "http://y", null);
        when(livenessCheckService.checkLiveness(id2)).thenReturn(result);

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));

        verify(livenessCheckService).checkLiveness(id1);
        verify(livenessCheckService).checkLiveness(id2);
    }

    @Test
    void execute_allJobsThrow_doesNotPropagate() {
        UUID id1 = UUID.randomUUID();
        when(query.getResultList()).thenReturn(List.of(id1));
        when(livenessCheckService.checkLiveness(id1)).thenThrow(new RuntimeException("fail"));

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));
    }

    @Test
    void execute_queryUsesCorrectSql() throws JobExecutionException {
        when(query.getResultList()).thenReturn(List.of());

        scheduler.execute(jobExecutionContext);

        verify(entityManager).createNativeQuery(
                "SELECT id FROM job_posting WHERE applied = true " +
                        "AND (last_liveness_check IS NULL OR last_liveness_check < :cutoff)");
        verify(query).setParameter(eq("cutoff"), any(LocalDateTime.class));
    }
}
