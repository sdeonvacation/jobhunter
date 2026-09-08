package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.repository.RecruiterPostCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecruiterPostDetectionServiceImplTest {

    @Mock
    private RecruiterPostCheckRepository recruiterPostCheckRepository;
    @Mock
    private JobContextResolver jobContextResolver;
    @Mock
    private PostSearchService postSearchService;
    @Mock
    private RecruiterPostAiTiebreaker tiebreaker;
    @Mock
    private OutreachContactRepository outreachContactRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private JobPostingRepository jobPostingRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LinkedInMcpProperties properties;
    private RecruiterPostDetectionServiceImpl service;

    private static final String JOB_URL = "https://jobs.lever.co/acme/123";

    @BeforeEach
    void setUp() {
        properties = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                new LinkedInMcpProperties.RateLimitConfig(20, 15, 10, 50),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000),
                new LinkedInMcpProperties.RecruiterPostCheckConfig(true, 6, 7, false, "past-month",
                        new LinkedInMcpProperties.AutomatedConfig(true, 10, 40, true)));
        service = new RecruiterPostDetectionServiceImpl(recruiterPostCheckRepository, jobContextResolver,
                postSearchService, tiebreaker, outreachContactRepository, companyRepository,
                jobPostingRepository, properties, objectMapper);
    }

    private JobContextResolver.JobContext ctx() {
        return new JobContextResolver.JobContext("Java Engineer", "Acme", "Berlin", null,
                JOB_URL, AtsType.LEVER, null, null);
    }

    private SignalScorer.CandidatePost highPost() {
        return new SignalScorer.CandidatePost("https://www.linkedin.com/posts/1", "Jane", "Recruiter at Acme",
                "https://linkedin.com/in/jane", "We are hiring a Java Engineer at Acme! Apply at " + JOB_URL, "2 days ago");
    }

    private SignalScorer.CandidatePost uncertainPost() {
        return new SignalScorer.CandidatePost("https://www.linkedin.com/posts/2", "Bob", "Engineer",
                "https://linkedin.com/in/bob", "Excited to start a new chapter in my career", "1 day ago");
    }

    private SignalScorer.CandidatePost mediumPost() {
        return new SignalScorer.CandidatePost("https://www.linkedin.com/posts/3", "Bob", "Engineer",
                "https://linkedin.com/in/bob", "Acme is looking for a Java Engineer to join the team", "1 day ago");
    }

    private Map<String, Object> resultData(RecruiterPostVerdict verdict, double confidence, int callsUsed) {
        Map<String, Object> data = new HashMap<>();
        data.put("verdict", verdict.name());
        data.put("confidence", confidence);
        data.put("matchedPosts", List.of());
        data.put("contactId", null);
        data.put("callsUsed", callsUsed);
        return data;
    }

    @Test
    @DisplayName("Fresh cache hit returns the cached result without external calls")
    void freshCacheHitReturnsCachedResult() {
        RecruiterPostCheck check = RecruiterPostCheck.builder()
                .jobUrl(JOB_URL)
                .verdict(RecruiterPostVerdict.HIGH)
                .confidence(0.9)
                .resultData(resultData(RecruiterPostVerdict.HIGH, 0.9, 2))
                .checkedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(recruiterPostCheckRepository.findByJobUrlAndExpiresAtAfter(eq(JOB_URL), any(LocalDateTime.class)))
                .thenReturn(Optional.of(check));

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.HIGH);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.callsUsed()).isEqualTo(2);
        verify(recruiterPostCheckRepository, never()).save(any(RecruiterPostCheck.class));
        verifyNoInteractions(jobContextResolver, postSearchService, tiebreaker, outreachContactRepository,
                companyRepository, jobPostingRepository);
    }

    @Test
    @DisplayName("force=true bypasses the cache")
    void forceBypassesCache() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.empty());

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, true);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.UNRESOLVED);
        verify(recruiterPostCheckRepository, never()).findByJobUrlAndExpiresAtAfter(anyString(), any(LocalDateTime.class));
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
    }

    @Test
    @DisplayName("Empty context resolution yields UNRESOLVED and persists the check")
    void unresolvedWhenContextEmpty() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.empty());

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.UNRESOLVED);
        assertThat(result.confidence()).isZero();
        assertThat(result.callsUsed()).isZero();
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
        verifyNoInteractions(postSearchService);
    }

    @Test
    @DisplayName("Empty candidate search yields NOT_FOUND and persists the check")
    void notFoundWhenSearchEmpty() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.of(ctx()));
        when(postSearchService.searchCandidates(any(JobContextResolver.JobContext.class), any(CallBudget.class),
                eq(false), eq("past-month"))).thenReturn(List.of());

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.NOT_FOUND);
        assertThat(result.confidence()).isZero();
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
    }

    @Test
    @DisplayName("HIGH match creates a contact and persists the check")
    void highMatchCreatesContactAndPersists() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.of(ctx()));
        when(postSearchService.searchCandidates(any(JobContextResolver.JobContext.class), any(CallBudget.class),
                eq(false), eq("past-month"))).thenReturn(List.of(highPost()));
        when(outreachContactRepository.findByLinkedinUrl("https://linkedin.com/in/jane")).thenReturn(Optional.empty());
        when(companyRepository.findByNormalizedName("acme")).thenReturn(Optional.empty());
        UUID contactId = UUID.randomUUID();
        when(outreachContactRepository.save(any(OutreachContact.class)))
                .thenReturn(OutreachContact.builder().id(contactId).build());

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.HIGH);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.matchedPosts()).hasSize(1);
        assertThat(result.contactId()).isEqualTo(contactId);
        verify(outreachContactRepository).save(any(OutreachContact.class));
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
        verifyNoInteractions(tiebreaker, jobPostingRepository);
    }

    @Test
    @DisplayName("Existing contact is reused without saving a new one")
    void existingContactReused() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.of(ctx()));
        when(postSearchService.searchCandidates(any(JobContextResolver.JobContext.class), any(CallBudget.class),
                eq(false), eq("past-month"))).thenReturn(List.of(highPost()));
        UUID existingId = UUID.randomUUID();
        when(outreachContactRepository.findByLinkedinUrl("https://linkedin.com/in/jane"))
                .thenReturn(Optional.of(OutreachContact.builder().id(existingId).build()));

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.HIGH);
        assertThat(result.contactId()).isEqualTo(existingId);
        verify(outreachContactRepository, never()).save(any(OutreachContact.class));
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
    }

    @Test
    @DisplayName("Contact creation failure still returns the check result with null contactId")
    void contactCreationFailureStillReturnsResult() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.of(ctx()));
        when(postSearchService.searchCandidates(any(JobContextResolver.JobContext.class), any(CallBudget.class),
                eq(false), eq("past-month"))).thenReturn(List.of(highPost()));
        when(outreachContactRepository.findByLinkedinUrl("https://linkedin.com/in/jane"))
                .thenThrow(new RuntimeException("db down"));

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.HIGH);
        assertThat(result.contactId()).isNull();
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
    }

    @Test
    @DisplayName("readCached returns only fresh rows from the repository")
    void readCachedReturnsFreshRows() {
        List<String> urls = List.of("https://a.example.com/job", "https://b.example.com/job");
        RecruiterPostCheck check = RecruiterPostCheck.builder()
                .jobUrl("https://a.example.com/job")
                .verdict(RecruiterPostVerdict.MEDIUM)
                .confidence(0.6)
                .resultData(resultData(RecruiterPostVerdict.MEDIUM, 0.6, 1))
                .checkedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(recruiterPostCheckRepository.findByJobUrlInAndExpiresAtAfter(eq(urls), any(LocalDateTime.class)))
                .thenReturn(List.of(check));

        List<RecruiterPostDetectionService.RecruiterPostCheckResult> results = service.readCached(urls);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).jobUrl()).isEqualTo("https://a.example.com/job");
        assertThat(results.get(0).verdict()).isEqualTo(RecruiterPostVerdict.MEDIUM);
        assertThat(results.get(0).confidence()).isEqualTo(0.6);
        assertThat(results.get(0).callsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("readCached with empty or null input returns empty without repository calls")
    void readCachedEmptyInput() {
        assertThat(service.readCached(List.of())).isEmpty();
        assertThat(service.readCached(null)).isEmpty();
        verify(recruiterPostCheckRepository, never()).findByJobUrlInAndExpiresAtAfter(any(), any());
    }

    @Test
    @DisplayName("Only keyword-level candidates yield UNCERTAIN with all posts as matches")
    void uncertainWhenOnlyKeywordLevelCandidates() {
        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.of(ctx()));
        when(postSearchService.searchCandidates(any(JobContextResolver.JobContext.class), any(CallBudget.class),
                eq(false), eq("past-month"))).thenReturn(List.of(uncertainPost()));

        RecruiterPostDetectionService.RecruiterPostCheckResult result = service.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.UNCERTAIN);
        assertThat(result.confidence()).isEqualTo(0.3);
        assertThat(result.matchedPosts()).hasSize(1);
        verify(recruiterPostCheckRepository).save(any(RecruiterPostCheck.class));
        verifyNoInteractions(tiebreaker);
    }

    @Test
    @DisplayName("AI tiebreaker downgrades a MEDIUM candidate to UNCERTAIN when it says NO")
    void aiRefineDowngradesMediumToUncertain() {
        LinkedInMcpProperties aiProps = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                new LinkedInMcpProperties.RateLimitConfig(20, 15, 10, 50),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000),
                new LinkedInMcpProperties.RecruiterPostCheckConfig(true, 6, 7, true, "past-month",
                        new LinkedInMcpProperties.AutomatedConfig(true, 10, 40, true)));
        RecruiterPostDetectionServiceImpl aiService = new RecruiterPostDetectionServiceImpl(
                recruiterPostCheckRepository, jobContextResolver, postSearchService, tiebreaker,
                outreachContactRepository, companyRepository, jobPostingRepository, aiProps, objectMapper);

        when(jobContextResolver.resolve(eq(JOB_URL), any(CallBudget.class))).thenReturn(Optional.of(ctx()));
        when(postSearchService.searchCandidates(any(JobContextResolver.JobContext.class), any(CallBudget.class),
                eq(false), eq("past-month"))).thenReturn(List.of(mediumPost()));
        when(tiebreaker.classify(any(SignalScorer.CandidatePost.class), any(JobContextResolver.JobContext.class)))
                .thenReturn(RecruiterPostAiTiebreaker.AiVerdict.NO);

        RecruiterPostDetectionService.RecruiterPostCheckResult result = aiService.checkRecruiterPost(JOB_URL, false);

        assertThat(result.verdict()).isEqualTo(RecruiterPostVerdict.UNCERTAIN);
        assertThat(result.confidence()).isEqualTo(0.3);
        verify(tiebreaker).classify(any(SignalScorer.CandidatePost.class), any(JobContextResolver.JobContext.class));
    }
}