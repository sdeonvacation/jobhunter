package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.linkedin.HttpMcpClient;
import dev.jobhunter.linkedin.LinkedInRateLimiter;
import dev.jobhunter.linkedin.ToolCategory;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpStrategyErrorTest {

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
    @DisplayName("Should return ERROR with message when all iterations throw")
    void shouldReturnErrorWhenAllIterationsFail() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_jobs"), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        FetchContext context = FetchContext.forSearch(
                List.of("java"), List.of("Berlin"), 200, 10, Map.of());

        FetchResult result = strategy.fetch(context);

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("All searches failed");
        assertThat(result.errorMessage()).contains("Connection refused");
        assertThat(result.errorMessage()).contains("(1)");
    }

    @Test
    @DisplayName("Should return ERROR with count when multiple iterations fail")
    void shouldReturnErrorWithCountForMultipleFailures() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_jobs"), any()))
                .thenThrow(new RuntimeException("Timeout"));

        FetchContext context = FetchContext.forSearch(
                List.of("java", "kotlin"), List.of("Berlin", "Munich"), 200, 10, Map.of());

        FetchResult result = strategy.fetch(context);

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("(4)");
        assertThat(result.errorMessage()).contains("Timeout");
    }

    @Test
    @DisplayName("Should return SUCCESS when some iterations succeed despite errors")
    void shouldReturnSuccessWhenSomeIterationsSucceed() {
        String searchText = "Backend Engineer\nAcme Corp\nBerlin (Hybrid)\n";
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sc = root.putObject("structuredContent");
        sc.putObject("sections").put("search_results", searchText);
        sc.putArray("job_ids").add("123");

        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_jobs"), any()))
                .thenReturn(root)
                .thenThrow(new RuntimeException("Timeout"));

        FetchContext context = FetchContext.forSearch(
                List.of("java", "kotlin"), List.of("Berlin"), 200, 10, Map.of());

        FetchResult result = strategy.fetch(context);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
    }

    @Test
    @DisplayName("Should return EMPTY (not ERROR) when no iterations fail but no jobs found")
    void shouldReturnEmptyWhenNoErrorsButNoJobs() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sc = root.putObject("structuredContent");
        sc.putObject("sections").put("search_results", "");
        sc.putArray("job_ids");

        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_jobs"), any())).thenReturn(root);

        FetchContext context = FetchContext.forSearch(
                List.of("java"), List.of("Berlin"), 200, 10, Map.of());

        FetchResult result = strategy.fetch(context);

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.errorMessage()).isNull();
    }
}
