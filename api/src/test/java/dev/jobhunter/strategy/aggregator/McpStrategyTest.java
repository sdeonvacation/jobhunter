package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.linkedin.HttpMcpClient;
import dev.jobhunter.linkedin.LinkedInRateLimiter;
import dev.jobhunter.linkedin.ToolCategory;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class McpStrategyTest {

    private HttpMcpClient httpMcpClient;
    private LinkedInRateLimiter rateLimiter;
    private McpStrategy strategy;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        httpMcpClient = mock(HttpMcpClient.class);
        rateLimiter = mock(LinkedInRateLimiter.class);
        strategy = new McpStrategy(httpMcpClient, rateLimiter);
    }

    @Test
    @DisplayName("name() returns linkedin-mcp")
    void nameReturnsLinkedinMcp() {
        assertThat(strategy.name()).isEqualTo("linkedin-mcp");
    }

    @Test
    @DisplayName("supports LINKEDIN AtsType")
    void supportsLinkedIn() {
        assertThat(strategy.supports(AtsType.LINKEDIN)).isTrue();
        assertThat(strategy.supports(AtsType.INDEED)).isFalse();
        assertThat(strategy.supports(AtsType.GREENHOUSE)).isFalse();
    }

    private JsonNode buildSearchResponse(String searchText, List<String> jobIds) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sc = root.putObject("structuredContent");
        ObjectNode sections = sc.putObject("sections");
        sections.put("search_results", searchText);
        ArrayNode ids = sc.putArray("job_ids");
        jobIds.forEach(ids::add);
        // No references → triggers positional fallback
        sc.putObject("references");
        return root;
    }

    private JsonNode buildSearchResponseWithRefs(String searchText, List<String[]> refs) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sc = root.putObject("structuredContent");
        ObjectNode sections = sc.putObject("sections");
        sections.put("search_results", searchText);
        sc.putArray("job_ids"); // empty, not used when refs present
        ObjectNode references = sc.putObject("references");
        ArrayNode searchResults = references.putArray("search_results");
        for (String[] ref : refs) {
            ObjectNode refNode = mapper.createObjectNode();
            refNode.put("kind", "job");
            refNode.put("url", "/jobs/view/" + ref[0] + "/");
            refNode.put("text", ref[1]);
            refNode.put("context", "job result");
            searchResults.add(refNode);
        }
        return root;
    }

    @Nested
    @DisplayName("fetch")
    class FetchTests {

        @Test
        @DisplayName("Should return empty when keywords are null")
        void shouldReturnEmptyForNullKeywords() {
            FetchContext context = FetchContext.forSearch(null, List.of("Berlin"), 200, 10, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should return empty when keywords are empty")
        void shouldReturnEmptyForEmptyKeywords() {
            FetchContext context = FetchContext.forSearch(List.of(), List.of("Berlin"), 200, 10, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should return empty when locations are null")
        void shouldReturnEmptyForNullLocations() {
            FetchContext context = FetchContext.forSearch(List.of("java"), null, 200, 10, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should return rate limited when first acquire fails")
        void shouldReturnRateLimitedOnFirstAcquireFail() {
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(false);
            FetchContext context = FetchContext.forSearch(List.of("java"), List.of("Berlin"), 200, 10, Map.of());

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("Should return partial results when rate limit hit mid-fetch")
        void shouldReturnPartialOnMidFetchRateLimit() {
            String searchText = "Backend Engineer\nAcme Corp\nBerlin (Hybrid)\n";
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true, false);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenReturn(buildSearchResponse(searchText, List.of("123")));

            FetchContext context = FetchContext.forSearch(
                    List.of("java", "kotlin"), List.of("Berlin"), 200, 10, Map.of());

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("Should fetch jobs successfully")
        void shouldFetchJobsSuccessfully() {
            String searchText = "Backend Engineer\nAcme Corp\nBerlin (Hybrid)\n"
                    + "Frontend Dev\nNewCo GmbH\nMunich (Remote)\n";
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenReturn(buildSearchResponse(searchText, List.of("111", "222")));

            FetchContext context = FetchContext.forSearch(
                    List.of("engineer"), List.of("Germany"), 200, 10, Map.of("date-posted", "week"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).externalId()).isEqualTo("111");
            assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer");
            assertThat(result.jobs().get(0).companyName()).isEqualTo("Acme Corp");
            assertThat(result.jobs().get(0).location()).isEqualTo("Berlin (Hybrid)");
            assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://www.linkedin.com/jobs/view/111/");
            assertThat(result.jobs().get(1).externalId()).isEqualTo("222");
            assertThat(result.jobs().get(1).companyName()).isEqualTo("NewCo GmbH");
        }

        @Test
        @DisplayName("Should iterate keywords x locations")
        void shouldIterateKeywordsAndLocations() {
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenReturn(buildSearchResponse("", List.of()));

            FetchContext context = FetchContext.forSearch(
                    List.of("java", "kotlin"), List.of("Berlin", "Munich"), 200, 10, Map.of());

            strategy.fetch(context);

            verify(httpMcpClient, times(4)).callTool(eq("search_jobs"), any());
        }

        @Test
        @DisplayName("Should stop when maxResults reached")
        void shouldStopAtMaxResults() {
            String searchText = "Dev\nCorp\nBerlin (Remote)\n";
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenReturn(buildSearchResponse(searchText, List.of("1")));

            FetchContext context = FetchContext.forSearch(
                    List.of("java", "kotlin"), List.of("Berlin", "Munich"), 1, 10, Map.of());

            FetchResult result = strategy.fetch(context);

            // Should stop after first search since maxResults=1 reached
            verify(httpMcpClient, times(1)).callTool(eq("search_jobs"), any());
            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("Should handle MCP client exception gracefully")
        void shouldHandleMcpException() {
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            FetchContext context = FetchContext.forSearch(
                    List.of("java"), List.of("Berlin"), 200, 10, Map.of());

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        }

        @Test
        @DisplayName("Should use default date-posted when config is null")
        void shouldUseDefaultDatePosted() {
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenReturn(buildSearchResponse("", List.of()));

            FetchContext context = FetchContext.forSearch(
                    List.of("java"), List.of("Berlin"), 200, 10, null);

            strategy.fetch(context);

            verify(httpMcpClient).callTool(eq("search_jobs"), argThat(params ->
                    "week".equals(params.get("date_posted"))));
        }
    }

    @Nested
    @DisplayName("parseSearchResponse")
    class ParseSearchResponseTests {

        @Test
        @DisplayName("Should return empty list for blank search text")
        void shouldReturnEmptyForBlankText() {
            JsonNode response = buildSearchResponse("", List.of("111"));
            assertThat(strategy.parseSearchResponse(response)).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list for non-array job_ids")
        void shouldReturnEmptyForNonArrayIds() {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode sc = root.putObject("structuredContent");
            sc.putObject("sections").put("search_results", "some text");
            sc.put("job_ids", "not-an-array");

            assertThat(strategy.parseSearchResponse(root)).isEmpty();
        }

        @Test
        @DisplayName("Should parse standard response with job_ids")
        void shouldParseStandardResponse() {
            String text = "Backend Engineer\nAcme\nBerlin (Hybrid)\n";
            JsonNode response = buildSearchResponse(text, List.of("job1"));

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(response);

            assertThat(jobs).hasSize(1);
            assertThat(jobs.get(0).externalId()).isEqualTo("job1");
            assertThat(jobs.get(0).title()).isEqualTo("Backend Engineer");
            assertThat(jobs.get(0).companyName()).isEqualTo("Acme");
            assertThat(jobs.get(0).applyUrl()).isEqualTo("https://www.linkedin.com/jobs/view/job1/");
        }

        @Test
        @DisplayName("Should skip batch when job_ids count mismatches parsed entries (no references)")
        void shouldSkipMismatchedCounts() {
            String text = "Engineer A\nCompany1\nBerlin (Remote)\n\n"
                    + "Engineer B\nCompany2\nMunich (Hybrid)\n";
            JsonNode response = buildSearchResponse(text, List.of("only-one"));

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(response);

            assertThat(jobs).isEmpty();
        }
    }

    @Nested
    @DisplayName("References-based alignment")
    class ReferencesAlignmentTests {

        @Test
        @DisplayName("Should use references for reliable ID-title-company alignment")
        void shouldAlignViaReferences() {
            String text = "Backend Engineer\nN26\nBerlin (Hybrid)\n\n"
                    + "Java Backend Engineer (m/f/x)\nosapiens\nMannheim (Hybrid)\n";
            List<String[]> refs = List.of(
                    new String[]{"111", "Backend Engineer"},
                    new String[]{"222", "Java Backend Engineer (m/f/x)"}
            );
            JsonNode response = buildSearchResponseWithRefs(text, refs);

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(response);

            assertThat(jobs).hasSize(2);
            assertThat(jobs.get(0).externalId()).isEqualTo("111");
            assertThat(jobs.get(0).title()).isEqualTo("Backend Engineer");
            assertThat(jobs.get(0).companyName()).isEqualTo("N26");
            assertThat(jobs.get(1).externalId()).isEqualTo("222");
            assertThat(jobs.get(1).title()).isEqualTo("Java Backend Engineer (m/f/x)");
            assertThat(jobs.get(1).companyName()).isEqualTo("osapiens");
        }

        @Test
        @DisplayName("Should handle references in different order than text")
        void shouldHandleDifferentOrder() {
            String text = "Engineer A\nCompany1\nBerlin (Remote)\n\n"
                    + "Engineer B\nCompany2\nMunich (Hybrid)\n";
            // References in reverse order compared to text
            List<String[]> refs = List.of(
                    new String[]{"222", "Engineer B"},
                    new String[]{"111", "Engineer A"}
            );
            JsonNode response = buildSearchResponseWithRefs(text, refs);

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(response);

            assertThat(jobs).hasSize(2);
            assertThat(jobs.get(0).externalId()).isEqualTo("222");
            assertThat(jobs.get(0).companyName()).isEqualTo("Company2");
            assertThat(jobs.get(1).externalId()).isEqualTo("111");
            assertThat(jobs.get(1).companyName()).isEqualTo("Company1");
        }

        @Test
        @DisplayName("Should still produce jobs when text has no match for a reference")
        void shouldProduceJobsWithoutTextMatch() {
            String text = "Engineer A\nCompany1\nBerlin (Remote)\n";
            List<String[]> refs = List.of(
                    new String[]{"111", "Engineer A"},
                    new String[]{"222", "Unknown Title Not In Text"}
            );
            JsonNode response = buildSearchResponseWithRefs(text, refs);

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(response);

            assertThat(jobs).hasSize(2);
            assertThat(jobs.get(0).externalId()).isEqualTo("111");
            assertThat(jobs.get(0).companyName()).isEqualTo("Company1");
            assertThat(jobs.get(1).externalId()).isEqualTo("222");
            assertThat(jobs.get(1).companyName()).isNull(); // no text match
        }

        @Test
        @DisplayName("Should handle duplicate titles with consume-once matching")
        void shouldHandleDuplicateTitles() {
            String text = "Backend Engineer\nN26\nBerlin (Hybrid)\n\n"
                    + "Backend Engineer\nFreenow\nHamburg (Hybrid)\n";
            List<String[]> refs = List.of(
                    new String[]{"111", "Backend Engineer"},
                    new String[]{"222", "Backend Engineer"}
            );
            JsonNode response = buildSearchResponseWithRefs(text, refs);

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(response);

            assertThat(jobs).hasSize(2);
            assertThat(jobs.get(0).externalId()).isEqualTo("111");
            assertThat(jobs.get(0).companyName()).isEqualTo("N26");
            assertThat(jobs.get(1).externalId()).isEqualTo("222");
            assertThat(jobs.get(1).companyName()).isEqualTo("Freenow");
        }

        @Test
        @DisplayName("Should skip non-job references")
        void shouldSkipNonJobReferences() {
            String text = "Engineer\nAcme\nBerlin (Remote)\n";

            ObjectNode root = mapper.createObjectNode();
            ObjectNode sc = root.putObject("structuredContent");
            sc.putObject("sections").put("search_results", text);
            sc.putArray("job_ids");
            ObjectNode references = sc.putObject("references");
            ArrayNode searchResults = references.putArray("search_results");

            // Add a company reference (should be skipped)
            ObjectNode companyRef = mapper.createObjectNode();
            companyRef.put("kind", "company");
            companyRef.put("url", "/company/acme/");
            companyRef.put("text", "Show more");
            searchResults.add(companyRef);

            // Add a job reference
            ObjectNode jobRef = mapper.createObjectNode();
            jobRef.put("kind", "job");
            jobRef.put("url", "/jobs/view/999/");
            jobRef.put("text", "Engineer");
            searchResults.add(jobRef);

            List<RawAggregatorJob> jobs = strategy.parseSearchResponse(root);

            assertThat(jobs).hasSize(1);
            assertThat(jobs.get(0).externalId()).isEqualTo("999");
            assertThat(jobs.get(0).companyName()).isEqualTo("Acme");
        }
    }

    @Nested
    @DisplayName("parseJobLines")
    class ParseJobLinesTests {

        @Test
        @DisplayName("Should parse standard format: title, company, location")
        void shouldParseStandardFormat() {
            String[] lines = {"Backend Engineer", "TechCo", "Berlin (Hybrid)"};
            var result = strategy.parseJobLines(lines);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Backend Engineer");
            assertThat(result.get(0).company()).isEqualTo("TechCo");
            assertThat(result.get(0).location()).isEqualTo("Berlin (Hybrid)");
        }

        @Test
        @DisplayName("Should parse multiple jobs")
        void shouldParseMultipleJobs() {
            String[] lines = {
                    "Engineer A", "Company1", "Berlin (Remote)",
                    "", "Engineer B", "Company2", "Munich (Hybrid)"
            };
            var result = strategy.parseJobLines(lines);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should skip noise lines like 'Set alert' and 'Jump to'")
        void shouldSkipNoiseLines() {
            String[] lines = {"Set alert", "Set alert for jobs", "Berlin (Remote)"};
            var result = strategy.parseJobLines(lines);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should skip 'with verification' lines")
        void shouldSkipVerificationLines() {
            String[] lines = {"Some title with verification", "Company", "Berlin (Remote)"};
            var result = strategy.parseJobLines(lines);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should recognize all work type location patterns")
        void shouldRecognizeAllLocationPatterns() {
            assertThat(strategy.parseJobLines(new String[]{"Dev", "Co", "Berlin (Hybrid)"})).hasSize(1);
            assertThat(strategy.parseJobLines(new String[]{"Dev", "Co", "Munich (Remote)"})).hasSize(1);
            assertThat(strategy.parseJobLines(new String[]{"Dev", "Co", "Hamburg (On-site)"})).hasSize(1);
            assertThat(strategy.parseJobLines(new String[]{"Dev", "Co", "Hamburg (On-Site)"})).hasSize(1);
            assertThat(strategy.parseJobLines(new String[]{"Dev", "Co", "Hamburg (Onsite)"})).hasSize(1);
        }

        @Test
        @DisplayName("Should ignore lines without work type indicator")
        void shouldIgnoreLinesWithoutWorkType() {
            String[] lines = {"Some Title", "Some Company", "Berlin, Germany"};
            var result = strategy.parseJobLines(lines);
            assertThat(result).isEmpty();
        }
    }
}
