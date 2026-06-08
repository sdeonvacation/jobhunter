package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LinkedInDescriptionEnricherTest {

    @Mock private HttpMcpClient httpMcpClient;
    @Mock private LinkedInRateLimiter rateLimiter;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private LanguageFilter languageFilter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LinkedInDescriptionEnricher enricher;

    @BeforeEach
    void setUp() {
        LinkedInMcpProperties properties = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                null, null,
                new LinkedInMcpProperties.EnrichmentConfig(true, 10, 0)
        );

        // Default: language filter keeps everything
        lenient().when(languageFilter.filter(any(), any())).thenReturn(FilterResult.keep());

        enricher = new LinkedInDescriptionEnricher(
                httpMcpClient, Optional.of(rateLimiter), properties, jobPostingRepository, languageFilter);
    }

    // --- enrich() gateway tests ---

    @Test
    void enrich_nonLinkedInSource_doesNothing() {
        enricher.enrich(JobSource.ARBEITNOW, 5);

        verify(jobPostingRepository, never()).findBySourceAndLanguageFilterAndDescriptionIsNull(any(), any());
    }

    @Test
    void enrich_zeroCreated_doesNothing() {
        enricher.enrich(JobSource.LINKEDIN, 0);

        verify(jobPostingRepository, never()).findBySourceAndLanguageFilterAndDescriptionIsNull(any(), any());
    }

    @Test
    void enrich_linkedInWithCreated_triggersEnrichment() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of());

        enricher.enrich(JobSource.LINKEDIN, 3);

        verify(jobPostingRepository).findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP);
    }

    // --- enrichDescriptions tests ---

    @Test
    void enrichDescriptions_structuredContentFormat_updatesDescription() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.LINKEDIN)
                .externalId("4252026496")
                .title("Backend Engineer")
                .description(null)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections")
                .put("description", "We are looking for a Backend Engineer with Java experience.");

        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "4252026496"))))
                .thenReturn(response);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("We are looking for a Backend Engineer with Java experience.");
    }

    @Test
    void enrichDescriptions_contentArrayFormat_updatesDescription() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.LINKEDIN)
                .externalId("123456")
                .title("Fullstack Dev")
                .description(null)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        String innerJson = "{\"sections\":{\"description\":\"Looking for a fullstack developer.\"}}";
        ObjectNode response = objectMapper.createObjectNode();
        var contentArray = response.putArray("content");
        ObjectNode contentItem = contentArray.addObject();
        contentItem.put("type", "text");
        contentItem.put("text", innerJson);

        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "123456"))))
                .thenReturn(response);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("Looking for a fullstack developer.");
    }

    @Test
    void enrichDescriptions_rateLimitReached_stopsProcessing() {
        JobPosting job1 = JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN)
                .externalId("111").title("Dev 1").description(null).build();
        JobPosting job2 = JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN)
                .externalId("222").title("Dev 2").description(null).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job1, job2));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(false);

        enricher.enrichDescriptions();

        verify(httpMcpClient, never()).callTool(anyString(), any());
        verify(jobPostingRepository, never()).save(any(JobPosting.class));
    }

    @Test
    void enrichDescriptions_mcpClientException_continuesWithNextJob() {
        JobPosting job1 = JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN)
                .externalId("111").title("Dev 1").description(null).build();
        JobPosting job2 = JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN)
                .externalId("222").title("Dev 2").description(null).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job1, job2));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "111"))))
                .thenThrow(new McpClientException("Session expired", -32001));

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections").put("description", "Good job posting");
        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "222"))))
                .thenReturn(response);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        verify(jobPostingRepository, times(1)).save(any(JobPosting.class));
        assertThat(job2.getDescription()).isEqualTo("Good job posting");
    }

    @Test
    void enrichDescriptions_enrichmentDisabled_doesNothing() {
        LinkedInMcpProperties disabledProps = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                null, null,
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000)
        );
        enricher = new LinkedInDescriptionEnricher(
                httpMcpClient, Optional.of(rateLimiter), disabledProps, jobPostingRepository, languageFilter);

        enricher.enrichDescriptions();

        verify(jobPostingRepository, never()).findBySourceAndLanguageFilterAndDescriptionIsNull(any(), any());
    }

    @Test
    void enrichDescriptions_respectsBatchSize() {
        LinkedInMcpProperties smallBatch = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                null, null,
                new LinkedInMcpProperties.EnrichmentConfig(true, 2, 0)
        );
        enricher = new LinkedInDescriptionEnricher(
                httpMcpClient, Optional.of(rateLimiter), smallBatch, jobPostingRepository, languageFilter);

        List<JobPosting> jobs = List.of(
                JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN).externalId("1").description(null).build(),
                JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN).externalId("2").description(null).build(),
                JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN).externalId("3").description(null).build(),
                JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN).externalId("4").description(null).build(),
                JobPosting.builder().id(UUID.randomUUID()).source(JobSource.LINKEDIN).externalId("5").description(null).build()
        );

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(jobs);
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections").put("description", "Some description");
        when(httpMcpClient.callTool(eq("get_job_details"), any())).thenReturn(response);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        verify(httpMcpClient, times(2)).callTool(eq("get_job_details"), any());
        verify(jobPostingRepository, times(2)).save(any(JobPosting.class));
    }

    @Test
    void enrichDescriptions_noJobsWithoutDescription_doesNothing() {
        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of());

        enricher.enrichDescriptions();

        verify(httpMcpClient, never()).callTool(anyString(), any());
    }

    @Test
    void enrichDescriptions_noRateLimiter_stillEnriches() {
        LinkedInMcpProperties properties = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                null, null,
                new LinkedInMcpProperties.EnrichmentConfig(true, 10, 0)
        );
        enricher = new LinkedInDescriptionEnricher(
                httpMcpClient, Optional.empty(), properties, jobPostingRepository, languageFilter);

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID()).source(JobSource.LINKEDIN)
                .externalId("999").title("Dev").description(null).build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job));

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections").put("description", "A description");
        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "999"))))
                .thenReturn(response);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        verify(jobPostingRepository).save(any(JobPosting.class));
        assertThat(job.getDescription()).isEqualTo("A description");
    }

    @Test
    void enrichDescriptions_languageFilterRejects_setsFilterDecisionToSkip() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.LINKEDIN)
                .externalId("777")
                .title("Softwareentwickler")
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .build();

        when(jobPostingRepository.findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.LINKEDIN, FilterDecision.KEEP))
                .thenReturn(List.of(job));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections")
                .put("description", "Wir suchen einen erfahrenen Softwareentwickler mit fließend Deutsch C1.");

        when(httpMcpClient.callTool(eq("get_job_details"), eq(Map.of("job_id", "777"))))
                .thenReturn(response);
        when(languageFilter.filter(eq("Softwareentwickler"), any()))
                .thenReturn(FilterResult.skip("German C1/C2 required"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(captor.getValue().getDescription()).contains("Wir suchen");
    }

    // --- extractDescription tests ---

    @Test
    void extractDescription_nullResponse_returnsNull() {
        assertThat(enricher.extractDescription(null)).isNull();
    }

    @Test
    void extractDescription_structuredContent_extractsDescription() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections")
                .put("description", "We need a Java dev");

        assertThat(enricher.extractDescription(response)).isEqualTo("We need a Java dev");
    }

    @Test
    void extractDescription_contentArrayWithJsonText_extractsFromSections() {
        ObjectNode response = objectMapper.createObjectNode();
        var arr = response.putArray("content");
        ObjectNode item = arr.addObject();
        item.put("type", "text");
        item.put("text", "{\"sections\":{\"description\":\"Parsed from JSON text\"}}");

        assertThat(enricher.extractDescription(response)).isEqualTo("Parsed from JSON text");
    }

    @Test
    void extractDescription_contentArrayWithPlainText_usesDirectly() {
        ObjectNode response = objectMapper.createObjectNode();
        var arr = response.putArray("content");
        ObjectNode item = arr.addObject();
        item.put("type", "text");
        item.put("text", "This is a plain text job description that is long enough to be used directly as content.");

        assertThat(enricher.extractDescription(response))
                .isEqualTo("This is a plain text job description that is long enough to be used directly as content.");
    }

    @Test
    void extractDescription_directDescriptionField_extractsIt() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("description", "Direct field description");

        assertThat(enricher.extractDescription(response)).isEqualTo("Direct field description");
    }

    @Test
    void extractDescription_emptyResponse_returnsNull() {
        ObjectNode response = objectMapper.createObjectNode();
        assertThat(enricher.extractDescription(response)).isNull();
    }

    @Test
    void extractDescription_blankDescription_returnsNull() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("structuredContent").putObject("sections").put("description", "   ");

        assertThat(enricher.extractDescription(response)).isNull();
    }
}
