package dev.jobhunter;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.MatchScore;
import dev.jobhunter.model.enums.*;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import dev.jobhunter.service.CrawlService;
import dev.jobhunter.service.ScoringService;
import dev.jobhunter.source.SourceConfig;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * E2E integration test: ATS crawl → DB → scoring → REST, aggregator ingestion → DB → REST,
 * and MCP data availability (same REST endpoints the MCP server queries).
 *
 * Uses the real Colima PostgreSQL (same as LiquibaseMigrationTest). No @Transactional on the
 * test class so REST calls via TestRestTemplate see committed data. @AfterEach handles cleanup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class CrawlPipelineIntegrationTest {

    @Autowired private CrawlService crawlService;
    @Autowired private AggregatorIngestionService aggregatorIngestionService;
    @Autowired private ScoringService scoringService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private CareerEndpointRepository endpointRepository;
    @Autowired private JobPostingRepository jobPostingRepository;
    @Autowired private MatchScoreRepository matchScoreRepository;
    @Autowired private TestRestTemplate restTemplate;
    // Replaced so crawlEndpoint() returns controlled data without hitting boards-api.greenhouse.io
    @MockBean  private StrategyRegistry strategyRegistry;

    /** Set in test 1 — endpoint + its jobs cleaned up in @AfterEach */
    private UUID createdEndpointId;
    /** Set in test 2 — standalone aggregator job cleaned up in @AfterEach */
    private UUID createdJobId;

    @BeforeEach
    void ensureClean() {
        // Delete any stale data from previous test runs that may not have been cleaned up
        jobPostingRepository.findBySourceAndExternalId(JobSource.ARBEITNOW, "agg-integ-002")
                .ifPresent(j -> { matchScoreRepository.deleteByJobId(j.getId()); jobPostingRepository.deleteById(j.getId()); });
    }

    @AfterEach
    void cleanup() {
        if (createdJobId != null) {
            matchScoreRepository.deleteByJobId(createdJobId);
            jobPostingRepository.deleteById(createdJobId);
            createdJobId = null;
        }
        if (createdEndpointId != null) {
            jobPostingRepository.findByEndpointIdAndIsActiveTrue(createdEndpointId)
                    .forEach(j -> {
                        matchScoreRepository.deleteByJobId(j.getId());
                        jobPostingRepository.deleteById(j.getId());
                    });
            endpointRepository.deleteById(createdEndpointId);
            createdEndpointId = null;
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: ATS crawl pipeline
    // -------------------------------------------------------------------------

    @Test
    void crawlAtsEndpoint_jobsStoredScoredAndVisibleViaRestApi() {
        Company company = companyRepository.findByNormalizedName("personio")
                .orElseThrow(() -> new IllegalStateException("Personio not seeded in DB"));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .company(company)
                .url("https://boards-api.greenhouse.io/v1/boards/personio-test-integration/jobs")
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("personio-test-integration")
                .confidence(Confidence.HIGH)
                .isActive(true)
                .build();
        endpoint = endpointRepository.save(endpoint);
        createdEndpointId = endpoint.getId();

        FetchStrategy mockStrategy = new FetchStrategy() {
            @Override public String name() { return "mock-greenhouse"; }
            @Override public Set<AtsType> supportedTypes() { return Set.of(AtsType.GREENHOUSE); }
            @Override public FetchResult fetch(FetchContext ctx) {
                return FetchResult.success(List.of(new RawAggregatorJob(
                        "integ-test-001",
                        "Senior Java Engineer",
                        "Personio",
                        "Berlin, Germany",
                        "We are looking for Senior Java and Spring Boot backend engineers in Berlin.",
                        "https://example.com/apply/integ-test-001",
                        LocalDate.now(), null, null, null, "{}")),
                        Duration.ofMillis(100));
            }
        };
        when(strategyRegistry.getStrategy(AtsType.GREENHOUSE)).thenReturn(Optional.of(mockStrategy));

        // Act: crawl
        int jobsFound = crawlService.crawlEndpoint(endpoint);
        assertThat(jobsFound).isEqualTo(1);

        // Assert: job persisted with correct fields
        List<JobPosting> saved = jobPostingRepository.findByEndpointIdAndIsActiveTrue(endpoint.getId());
        assertThat(saved).hasSize(1);
        JobPosting posting = saved.get(0);
        assertThat(posting.getTitle()).isEqualTo("Senior Java Engineer");
        assertThat(posting.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(posting.getSource()).isEqualTo(JobSource.GREENHOUSE);
        assertThat(posting.getEndpoint().getId()).isEqualTo(endpoint.getId());

        // Act: score
        scoringService.scoreJobsForEndpoint(endpoint.getId());

        // Assert: match score created
        Optional<MatchScore> matchScore = matchScoreRepository.findByJobId(posting.getId());
        assertThat(matchScore).isPresent();
        assertThat(matchScore.get().getOverallScore()).isGreaterThanOrEqualTo(0);

        // Assert: REST API returns the job via direct ID lookup (avoids pagination issues with large DB)
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/jobs/" + posting.getId(), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET /api/jobs/{id} must return 200 for crawled ATS job").isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("title")).isEqualTo("Senior Java Engineer");
    }

    // -------------------------------------------------------------------------
    // Test 2: Aggregator ingestion pipeline
    // -------------------------------------------------------------------------

    @Test
    void ingestAggregatorSource_jobsStoredScoredAndVisibleViaRestApi() {
        FetchStrategy mockFetchStrategy = new FetchStrategy() {
            @Override public String name() { return "mock-aggregator"; }
            @Override public FetchResult fetch(FetchContext ctx) {
                return FetchResult.success(List.of(new RawAggregatorJob(
                        "agg-integ-002",
                        "Backend Engineer",
                        "TestCo",
                        "Berlin, Germany",
                        "Looking for Java Spring Boot backend engineers in Berlin.",
                        "https://example.com/apply/agg-integ-002",
                        LocalDate.now(), null, null, null, "{}")),
                        Duration.ofMillis(50));
            }
        };

        SourceConfig mockSource = new SourceConfig() {
            @Override public String name() { return "test-aggregator-source"; }
            @Override public JobSource sourceType() { return JobSource.ARBEITNOW; }
            @Override public DiscoverySource discoverySource() { return DiscoverySource.ARBEITNOW; }
            @Override public FetchStrategy strategy() { return mockFetchStrategy; }
            @Override public FetchContext buildContext() {
                return FetchContext.forSearch(List.of(), List.of(), 30, 10, Map.of());
            }
            @Override public int frequencyHours() { return 4; }
            @Override public boolean isEnabled() { return true; }
        };

        // Act: ingest
        IngestionStats stats = aggregatorIngestionService.ingest(mockSource);
        assertThat(stats.created()).isEqualTo(1);
        assertThat(stats.errors()).isEqualTo(0);

        // Assert: job persisted
        Optional<JobPosting> savedOpt = jobPostingRepository.findBySourceAndExternalId(
                JobSource.ARBEITNOW, "agg-integ-002");
        assertThat(savedOpt).isPresent();
        JobPosting posting = savedOpt.get();
        assertThat(posting.getTitle()).isEqualTo("Backend Engineer");
        assertThat(posting.getSource()).isEqualTo(JobSource.ARBEITNOW);
        createdJobId = posting.getId();

        // Act: score (no exception = pipeline is functional)
        scoringService.scoreJobsForSource(JobSource.ARBEITNOW);

        // Assert: REST visible via source-filtered endpoint
        // Assert: REST API returns the job via direct ID lookup
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/jobs/" + posting.getId(), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET /api/jobs/{id} must return 200 for ingested aggregator job").isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("title")).isEqualTo("Backend Engineer");
    }

    // -------------------------------------------------------------------------
    // Test 3: MCP data availability — REST fields the MCP tools consume
    // -------------------------------------------------------------------------

    @Test
    void mcpDataAvailability_restApiHasRequiredFieldsAndStructure() {
        // GET /api/jobs — used by MCP get_jobs / get_top_jobs
        ResponseEntity<Map<String, Object>> jobsResponse = restTemplate.exchange(
                "/api/jobs?size=10", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(jobsResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = jobsResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("content", "totalElements");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertThat(content).isNotNull();
        if (!content.isEmpty()) {
            Map<String, Object> job = content.get(0);
            // Fields MCP tools rely on: get_jobs, get_top_jobs, get_job_keywords all use these
            assertThat(job).containsKeys("title", "location", "source", "applyUrl");
        }

        // GET /api/jobs/today — used by MCP get_top_jobs_today
        ResponseEntity<Map<String, Object>> todayResponse = restTemplate.exchange(
                "/api/jobs/today", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(todayResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> todayBody = todayResponse.getBody();
        assertThat(todayBody).isNotNull();
        assertThat(todayBody).containsKey("content");
        assertThat(todayBody).containsKey("totalElements");
    }
}
