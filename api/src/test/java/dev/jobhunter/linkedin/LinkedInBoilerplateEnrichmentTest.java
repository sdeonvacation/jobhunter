package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.service.MatchScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that LinkedInDescriptionEnricher re-selects boilerplate-description rows
 * (LinkedIn apply-URL meta description) via
 * findBySourceAndLanguageFilterAndDescriptionNullOrBoilerplate and replaces them
 * with real JDs from the MCP. Lives here (not LinkedInDescriptionEnricherTest)
 * because LinkedInDescriptionEnricherTest is excluded from compileTestJava.
 */
@ExtendWith(MockitoExtension.class)
class LinkedInBoilerplateEnrichmentTest {

    private static final String BOILERPLATE =
            "Apply for Backend Engineer jobs at Example Corp on LinkedIn. See responsibilities, "
                    + "qualifications, and similar jobs on LinkedIn.";
    private static final String REAL_JD =
            "We are hiring a Backend Engineer with 5+ years of Java and Spring Boot experience "
                    + "to build distributed systems across our platform teams.";

    @Mock private HttpMcpClient httpMcpClient;
    @Mock private LinkedInRateLimiter rateLimiter;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private DescriptionFilterChain descriptionFilterChain;
    @Mock private MatchScoringService matchScoringService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LinkedInDescriptionEnricher enricher;

    @BeforeEach
    void setUp() {
        LinkedInMcpProperties properties = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                null, null,
                new LinkedInMcpProperties.EnrichmentConfig(true, 10, 0),
                new LinkedInMcpProperties.RecruiterPostCheckConfig(true, 6, 7, true, "past-month",
                        new LinkedInMcpProperties.AutomatedConfig(true, 10, 40, true)));

        enricher = new LinkedInDescriptionEnricher(
                httpMcpClient, Optional.of(rateLimiter), properties,
                jobPostingRepository, descriptionFilterChain, matchScoringService);
    }

    private JobPosting boilerplateJob() {
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.LINKEDIN)
                .externalId("4252026496")
                .title("Backend Engineer")
                .description(BOILERPLATE)
                .languageFilter(FilterDecision.KEEP)
                .build();
    }

    @Test
    void doBackfill_boilerplateRow_replacedWithRealJd_refilteredAndSaved() {
        JobPosting job = boilerplateJob();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionNullOrBoilerplate(
                JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections")
                .put("description", REAL_JD);
        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "4252026496"))))
                .thenReturn(response);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.backfill();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo(REAL_JD);
        assertThat(job.getDescription()).isEqualTo(REAL_JD);
        verify(descriptionFilterChain).refilter(job);
        // Old description-IS-NULL query must no longer be the candidate source
        verify(jobPostingRepository, never())
                .findBySourceAndLanguageFilterAndDescriptionIsNull(any(), any());
    }

    @Test
    void doBackfill_mcpReturnsNullDescription_rowUnchanged_noSave() {
        JobPosting job = boilerplateJob();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionNullOrBoilerplate(
                JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);
        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "4252026496"))))
                .thenReturn(null);

        enricher.backfill();

        // Stays a candidate (boilerplate untouched) — no refilter, no save
        assertThat(job.getDescription()).isEqualTo(BOILERPLATE);
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        verify(descriptionFilterChain, never()).refilter(any(JobPosting.class));
    }

    @Test
    void doBackfill_emptyCandidateList_doesNothing() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionNullOrBoilerplate(
                JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of());

        enricher.backfill();

        verify(httpMcpClient, never()).callTool(anyString(), any());
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
        verify(descriptionFilterChain, never()).refilter(any(JobPosting.class));
    }

    @Test
    void doBackfill_queriesNullOrBoilerplateMethod_notDescriptionIsNull() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionNullOrBoilerplate(
                JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of());

        enricher.backfill();

        verify(jobPostingRepository, times(1))
                .findBySourceAndLanguageFilterAndDescriptionNullOrBoilerplate(
                        JobSource.LINKEDIN, FilterDecision.KEEP);
        verify(jobPostingRepository, never())
                .findBySourceAndLanguageFilterAndDescriptionIsNull(any(), any());
    }
}
