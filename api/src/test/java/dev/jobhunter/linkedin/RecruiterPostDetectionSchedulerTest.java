package dev.jobhunter.linkedin;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.OpportunityScore;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OpportunityScoreRepository;
import dev.jobhunter.repository.RecruiterPostCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecruiterPostDetectionSchedulerTest {

    @Mock
    private RecruiterPostDetectionService recruiterPostDetectionService;
    @Mock
    private RecruiterPostCheckRepository recruiterPostCheckRepository;
    @Mock
    private JobPostingRepository jobPostingRepository;
    @Mock
    private OpportunityScoreRepository opportunityScoreRepository;
    @Mock
    private HttpMcpClient httpMcpClient;

    private LinkedInMcpProperties properties;
    private RecruiterPostDetectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                new LinkedInMcpProperties.RateLimitConfig(20, 15, 10, 50),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000),
                new LinkedInMcpProperties.RecruiterPostCheckConfig(true, 6, 7, false, "past-month",
                        new LinkedInMcpProperties.AutomatedConfig(true, 10, 40, true)));
        scheduler = new RecruiterPostDetectionScheduler(recruiterPostDetectionService, recruiterPostCheckRepository,
                jobPostingRepository, opportunityScoreRepository, httpMcpClient, properties);
    }

    private JobPosting job(String title, String applyUrl) {
        return JobPosting.builder().id(UUID.randomUUID()).title(title).applyUrl(applyUrl).build();
    }

    private OpportunityScore score(int value) {
        return OpportunityScore.builder().score(value).build();
    }

    private void stubJobSelection(List<JobPosting> jobs) {
        Page<JobPosting> page = mock(Page.class);
        when(page.getContent()).thenReturn(jobs);
        when(jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndDiscoveredDate(
                eq(FilterDecision.KEEP), any(LocalDate.class), any(Pageable.class))).thenReturn(page);
        when(opportunityScoreRepository.findByJobId(any(UUID.class))).thenReturn(Optional.of(score(50)));
    }

    @Test
    @DisplayName("Disabled config skips the batch without touching collaborators")
    void disabledConfigSkipsBatch() {
        LinkedInMcpProperties disabled = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                new LinkedInMcpProperties.RateLimitConfig(20, 15, 10, 50),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000),
                new LinkedInMcpProperties.RecruiterPostCheckConfig(false, 6, 7, false, "past-month",
                        new LinkedInMcpProperties.AutomatedConfig(true, 10, 40, true)));
        RecruiterPostDetectionScheduler disabledScheduler = new RecruiterPostDetectionScheduler(
                recruiterPostDetectionService, recruiterPostCheckRepository, jobPostingRepository,
                opportunityScoreRepository, httpMcpClient, disabled);

        disabledScheduler.runBatch();

        verifyNoInteractions(recruiterPostDetectionService, recruiterPostCheckRepository,
                jobPostingRepository, opportunityScoreRepository, httpMcpClient);
    }

    @Test
    @DisplayName("Invalid MCP session skips the batch")
    void invalidSessionSkipsBatch() {
        when(httpMcpClient.isSessionValid()).thenReturn(false);

        scheduler.runBatch();

        verifyNoInteractions(recruiterPostDetectionService, recruiterPostCheckRepository,
                jobPostingRepository, opportunityScoreRepository);
    }

    @Test
    @DisplayName("selectTopNJobs orders by opportunity score descending")
    void selectTopNJobsOrdersByScoreDesc() {
        JobPosting low = job("low", "https://low.example.com/job");
        JobPosting high = job("high", "https://high.example.com/job");
        JobPosting mid = job("mid", "https://mid.example.com/job");
        Page<JobPosting> page = mock(Page.class);
        when(page.getContent()).thenReturn(List.of(low, high, mid));
        when(jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndDiscoveredDate(
                eq(FilterDecision.KEEP), any(LocalDate.class), any(Pageable.class))).thenReturn(page);
        when(opportunityScoreRepository.findByJobId(low.getId())).thenReturn(Optional.of(score(10)));
        when(opportunityScoreRepository.findByJobId(high.getId())).thenReturn(Optional.of(score(30)));
        when(opportunityScoreRepository.findByJobId(mid.getId())).thenReturn(Optional.of(score(20)));

        List<JobPosting> top = scheduler.selectTopNJobs(2);

        assertThat(top).containsExactly(high, mid);
    }

    @Test
    @DisplayName("Jobs with a fresh cached check are skipped")
    void freshCacheJobsSkipped() {
        JobPosting cached = job("cached", "https://cached.example.com/job");
        JobPosting fresh = job("fresh", "https://fresh.example.com/job");
        stubJobSelection(List.of(cached, fresh));
        when(httpMcpClient.isSessionValid()).thenReturn(true);
        when(recruiterPostCheckRepository.findByJobUrlAndExpiresAtAfter(
                eq("https://cached.example.com/job"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(RecruiterPostCheck.builder()
                        .jobUrl("https://cached.example.com/job")
                        .verdict(RecruiterPostVerdict.HIGH)
                        .confidence(0.9)
                        .checkedAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusDays(1))
                        .build()));
        when(recruiterPostCheckRepository.findByJobUrlAndExpiresAtAfter(
                eq("https://fresh.example.com/job"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(recruiterPostDetectionService.checkRecruiterPost("https://fresh.example.com/job", false))
                .thenReturn(new RecruiterPostDetectionService.RecruiterPostCheckResult(
                        "https://fresh.example.com/job", RecruiterPostVerdict.HIGH, 0.9, List.of(), null, 1));

        scheduler.runBatch();

        verify(recruiterPostDetectionService, never()).checkRecruiterPost("https://cached.example.com/job", false);
        verify(recruiterPostDetectionService).checkRecruiterPost("https://fresh.example.com/job", false);
    }

    @Test
    @DisplayName("Daily call budget cap stops the batch")
    void dailyBudgetCapStopsBatch() {
        JobPosting first = job("first", "https://first.example.com/job");
        JobPosting second = job("second", "https://second.example.com/job");
        stubJobSelection(List.of(first, second));
        when(httpMcpClient.isSessionValid()).thenReturn(true);
        when(recruiterPostCheckRepository.findByJobUrlAndExpiresAtAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(recruiterPostDetectionService.checkRecruiterPost("https://first.example.com/job", false))
                .thenReturn(new RecruiterPostDetectionService.RecruiterPostCheckResult(
                        "https://first.example.com/job", RecruiterPostVerdict.HIGH, 0.9, List.of(), null, 40));

        scheduler.runBatch();

        verify(recruiterPostDetectionService).checkRecruiterPost("https://first.example.com/job", false);
        verify(recruiterPostDetectionService, never()).checkRecruiterPost("https://second.example.com/job", false);
    }

    @Test
    @DisplayName("A per-job exception does not abort the batch")
    void perJobExceptionDoesNotAbortBatch() {
        JobPosting bad = job("bad", "https://bad.example.com/job");
        JobPosting good = job("good", "https://good.example.com/job");
        stubJobSelection(List.of(bad, good));
        when(httpMcpClient.isSessionValid()).thenReturn(true);
        when(recruiterPostCheckRepository.findByJobUrlAndExpiresAtAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(recruiterPostDetectionService.checkRecruiterPost("https://bad.example.com/job", false))
                .thenThrow(new RuntimeException("boom"));
        when(recruiterPostDetectionService.checkRecruiterPost("https://good.example.com/job", false))
                .thenReturn(new RecruiterPostDetectionService.RecruiterPostCheckResult(
                        "https://good.example.com/job", RecruiterPostVerdict.HIGH, 0.9, List.of(), null, 1));

        scheduler.runBatch();

        verify(recruiterPostDetectionService).checkRecruiterPost("https://bad.example.com/job", false);
        verify(recruiterPostDetectionService).checkRecruiterPost("https://good.example.com/job", false);
    }

    @Test
    @DisplayName("A second runBatch while one is running is a no-op")
    void secondRunBatchWhileRunningIsNoOp() throws Exception {
        Field runningField = RecruiterPostDetectionScheduler.class.getDeclaredField("running");
        runningField.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) runningField.get(scheduler);
        running.set(true);

        scheduler.runBatch();

        verifyNoInteractions(recruiterPostDetectionService, recruiterPostCheckRepository,
                jobPostingRepository, opportunityScoreRepository, httpMcpClient);
    }
}