package dev.jobhunter.scheduler;

import dev.jobhunter.strategy.aggregator.GlobalMoveSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalMoveKeepaliveJobTest {

    @Mock private GlobalMoveSessionManager sessionManager;
    @Mock private JobExecutionContext jobExecutionContext;

    @Test
    void execute_unconfigured_skipsPing() {
        when(sessionManager.isConfigured()).thenReturn(false);
        GlobalMoveKeepaliveJob job = new GlobalMoveKeepaliveJob(sessionManager);

        assertDoesNotThrow(() -> job.execute(jobExecutionContext));

        verify(sessionManager, never()).ping();
    }

    @Test
    void execute_configured_pingsOnce() {
        when(sessionManager.isConfigured()).thenReturn(true);
        GlobalMoveKeepaliveJob job = new GlobalMoveKeepaliveJob(sessionManager);

        assertDoesNotThrow(() -> job.execute(jobExecutionContext));

        verify(sessionManager).ping();
    }

    @Test
    void execute_pingThrows_swallowed() {
        when(sessionManager.isConfigured()).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(sessionManager).ping();
        GlobalMoveKeepaliveJob job = new GlobalMoveKeepaliveJob(sessionManager);

        assertDoesNotThrow(() -> job.execute(jobExecutionContext));
    }
}