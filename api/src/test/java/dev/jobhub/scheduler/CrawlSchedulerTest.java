package dev.jobhub.scheduler;

import dev.jobhub.service.CrawlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrawlSchedulerTest {

    @Mock private CrawlService crawlService;
    @Mock private JobExecutionContext jobExecutionContext;
    @InjectMocks private CrawlScheduler scheduler;

    @Test
    void execute_callsCrawlService() throws JobExecutionException {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{5, 20, 1});

        scheduler.execute(jobExecutionContext);

        verify(crawlService).crawlAllDueEndpoints();
    }

    @Test
    void execute_exceptionDoesNotPropagate() {
        when(crawlService.crawlAllDueEndpoints()).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));

        verify(crawlService).crawlAllDueEndpoints();
    }

    @Test
    void execute_logsStatsOnSuccess() throws JobExecutionException {
        when(crawlService.crawlAllDueEndpoints()).thenReturn(new int[]{3, 15, 0});

        scheduler.execute(jobExecutionContext);

        verify(crawlService, times(1)).crawlAllDueEndpoints();
    }
}
