package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.resolution.AtsDetector;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobContextResolverImplTest {

    @Mock
    private JobPostingRepository jobPostingRepository;
    @Mock
    private HttpMcpClient httpMcpClient;
    @Mock
    private LinkedInRateLimiter rateLimiter;
    @Mock
    private AtsDetector atsDetector;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobContextResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new JobContextResolverImpl(jobPostingRepository, httpMcpClient, rateLimiter, atsDetector, objectMapper);
    }

    @Test
    @DisplayName("DB hit by apply URL resolves JobContext with jobPostingId and company name")
    void dbHitByApplyUrl() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().name("Acme GmbH").build();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .title("Java Engineer")
                .company(company)
                .location("Berlin")
                .postedDate(LocalDate.of(2026, 9, 1))
                .applyUrl("https://jobs.lever.co/acme/123")
                .build();
        String url = "https://jobs.lever.co/acme/123";
        when(jobPostingRepository.findFirstByApplyUrl(url)).thenReturn(Optional.of(job));
        when(atsDetector.detectFromUrl(url)).thenReturn(
                Optional.of(new AtsDetector.DetectionResult(AtsType.LEVER, Confidence.HIGH, "acme")));

        Optional<JobContextResolver.JobContext> result = resolver.resolve(url, new CallBudget(6));

        assertThat(result).isPresent();
        JobContextResolver.JobContext ctx = result.get();
        assertThat(ctx.title()).isEqualTo("Java Engineer");
        assertThat(ctx.company()).isEqualTo("Acme GmbH");
        assertThat(ctx.location()).isEqualTo("Berlin");
        assertThat(ctx.postedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(ctx.applyUrl()).isEqualTo(url);
        assertThat(ctx.atsType()).isEqualTo(AtsType.LEVER);
        assertThat(ctx.linkedinJobId()).isNull();
        assertThat(ctx.jobPostingId()).isEqualTo(jobId);
        verifyNoInteractions(httpMcpClient);
    }

    @Test
    @DisplayName("DB prefix lookup resolves when exact apply URL is absent")
    void dbPrefixHit() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .title("Java Engineer")
                .company(Company.builder().name("Acme").build())
                .applyUrl("https://jobs.lever.co/acme/123")
                .build();
        String url = "https://jobs.lever.co/acme/123";
        when(jobPostingRepository.findFirstByApplyUrl(url)).thenReturn(Optional.empty());
        when(jobPostingRepository.findFirstByApplyUrlStartingWith(url)).thenReturn(Optional.of(job));

        Optional<JobContextResolver.JobContext> result = resolver.resolve(url, new CallBudget(6));

        assertThat(result).isPresent();
        assertThat(result.get().jobPostingId()).isEqualTo(jobId);
        verify(jobPostingRepository).findFirstByApplyUrlStartingWith(url);
        verifyNoInteractions(httpMcpClient);
    }

    @Test
    @DisplayName("LinkedIn URL resolves via get_job_details with budget and PROFILE rate limit")
    void linkedInUrlResolvesViaMcp() throws Exception {
        String url = "https://www.linkedin.com/jobs/view/123456789";
        CallBudget budget = new CallBudget(6);
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);
        JsonNode response = objectMapper.readTree(
                "{\"title\":\"Java Engineer\",\"company\":\"Acme\",\"location\":\"Berlin\"}");
        when(httpMcpClient.callTool(eq("get_job_details"), anyMap())).thenReturn(response);

        Optional<JobContextResolver.JobContext> result = resolver.resolve(url, budget);

        assertThat(result).isPresent();
        JobContextResolver.JobContext ctx = result.get();
        assertThat(ctx.title()).isEqualTo("Java Engineer");
        assertThat(ctx.company()).isEqualTo("Acme");
        assertThat(ctx.location()).isEqualTo("Berlin");
        assertThat(ctx.linkedinJobId()).isEqualTo("123456789");
        assertThat(ctx.atsType()).isEqualTo(AtsType.LINKEDIN);
        assertThat(ctx.jobPostingId()).isNull();
        assertThat(budget.used()).isEqualTo(1);
        verify(httpMcpClient).callTool(eq("get_job_details"), argThat(m -> "123456789".equals(m.get("job_id"))));
    }

    @Test
    @DisplayName("ATS URL resolves via Jsoup OpenGraph meta tags")
    void atsUrlResolvesViaJsoup() throws IOException {
        String url = "https://careers.acme.com/jobs/123";
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"Senior Java Engineer at Acme GmbH\">"
                + "<meta property=\"og:site_name\" content=\"Acme GmbH\">"
                + "</head></html>";
        Document doc = Jsoup.parse(html);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            Connection connection = mock(Connection.class);
            when(Jsoup.connect(url)).thenReturn(connection);
            when(connection.timeout(anyInt())).thenReturn(connection);
            when(connection.userAgent(anyString())).thenReturn(connection);
            when(connection.get()).thenReturn(doc);

            Optional<JobContextResolver.JobContext> result = resolver.resolve(url, new CallBudget(6));

            assertThat(result).isPresent();
            JobContextResolver.JobContext ctx = result.get();
            assertThat(ctx.title()).isEqualTo("Senior Java Engineer");
            assertThat(ctx.company()).isEqualTo("Acme GmbH");
            assertThat(ctx.atsType()).isNull();
            assertThat(ctx.linkedinJobId()).isNull();
            assertThat(ctx.jobPostingId()).isNull();
        }
        verifyNoInteractions(httpMcpClient);
    }

    @Test
    @DisplayName("LinkedIn URL without numeric job id is unresolvable without MCP calls")
    void linkedInUrlWithoutNumericIdUnresolvable() {
        String url = "https://www.linkedin.com/jobs/view/abc";
        CallBudget budget = new CallBudget(6);

        Optional<JobContextResolver.JobContext> result = resolver.resolve(url, budget);

        assertThat(result).isEmpty();
        assertThat(budget.used()).isZero();
        verify(httpMcpClient, never()).callTool(anyString(), anyMap());
    }

    @Test
    @DisplayName("LinkedIn URL is unresolvable when PROFILE rate limit is reached")
    void linkedInUrlRateLimited() {
        String url = "https://www.linkedin.com/jobs/view/123456789";
        CallBudget budget = new CallBudget(6);
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(false);

        Optional<JobContextResolver.JobContext> result = resolver.resolve(url, budget);

        assertThat(result).isEmpty();
        assertThat(budget.used()).isEqualTo(1);
        verify(httpMcpClient, never()).callTool(anyString(), anyMap());
    }

    @Test
    @DisplayName("LinkedIn URL is unresolvable when the call budget is exhausted")
    void linkedInUrlBudgetExhausted() {
        String url = "https://www.linkedin.com/jobs/view/123456789";

        Optional<JobContextResolver.JobContext> result = resolver.resolve(url, new CallBudget(0));

        assertThat(result).isEmpty();
        verify(httpMcpClient, never()).callTool(anyString(), anyMap());
    }

    @Test
    @DisplayName("ATS fetch failure yields empty result without MCP calls")
    void atsFetchFailureUnresolvable() throws IOException {
        String url = "https://careers.acme.com/jobs/123";

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            Connection connection = mock(Connection.class);
            when(Jsoup.connect(url)).thenReturn(connection);
            when(connection.timeout(anyInt())).thenReturn(connection);
            when(connection.userAgent(anyString())).thenReturn(connection);
            when(connection.get()).thenThrow(new IOException("connection refused"));

            Optional<JobContextResolver.JobContext> result = resolver.resolve(url, new CallBudget(6));

            assertThat(result).isEmpty();
        }
        verify(httpMcpClient, never()).callTool(anyString(), anyMap());
    }

    @Test
    @DisplayName("Blank URL is unresolvable")
    void blankUrlUnresolvable() {
        assertThat(resolver.resolve(null, new CallBudget(6))).isEmpty();
        assertThat(resolver.resolve("   ", new CallBudget(6))).isEmpty();
        verifyNoInteractions(jobPostingRepository, httpMcpClient, rateLimiter, atsDetector);
    }
}