package dev.jobhub.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class HttpMcpClientImplTest {

    private HttpMcpClientImpl client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        objectMapper = new ObjectMapper();

        LinkedInMcpProperties properties = new LinkedInMcpProperties(
                true,
                wmInfo.getHttpBaseUrl(),
                "/mcp",
                30,
                new LinkedInMcpProperties.RateLimitConfig(20, 15, 10, 50),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000)
        );

        LinkedInRateLimiter rateLimiter = new NoOpRateLimiter();

        client = new HttpMcpClientImpl(
                WebClient.builder(),
                properties,
                rateLimiter,
                objectMapper
        );
    }

    @Test
    @DisplayName("Should send proper JSON-RPC 2.0 request and parse result")
    void shouldSendJsonRpcRequest() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("name", "Test User");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("result", result);

        stubFor(post("/mcp")
                .willReturn(okJson(objectMapper.writeValueAsString(response))));

        JsonNode actual = client.callTool("get_my_profile", Map.of());

        assertThat(actual.get("name").asText()).isEqualTo("Test User");

        verify(postRequestedFor(urlEqualTo("/mcp"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.jsonrpc", equalTo("2.0")))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("get_my_profile"))));
    }

    @Test
    @DisplayName("Should pass tool arguments correctly")
    void shouldPassToolArguments() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("count", 5);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("result", result);

        stubFor(post("/mcp")
                .willReturn(okJson(objectMapper.writeValueAsString(response))));

        client.callTool("search_jobs", Map.of("keywords", "java", "location", "Berlin"));

        verify(postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$.params.arguments.keywords", equalTo("java")))
                .withRequestBody(matchingJsonPath("$.params.arguments.location", equalTo("Berlin"))));
    }

    @Test
    @DisplayName("Should throw McpClientException on JSON-RPC error")
    void shouldThrowOnJsonRpcError() throws Exception {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", -32600);
        error.put("message", "Invalid request");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("error", error);

        stubFor(post("/mcp")
                .willReturn(okJson(objectMapper.writeValueAsString(response))));

        assertThatThrownBy(() -> client.callTool("bad_tool", Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessage("Invalid request");
    }

    @Test
    @DisplayName("Should mark session invalid on -32001 error code")
    void shouldMarkSessionInvalidOnExpiredError() throws Exception {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", -32001);
        error.put("message", "Session expired");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("error", error);

        stubFor(post("/mcp")
                .willReturn(okJson(objectMapper.writeValueAsString(response))));

        assertThatThrownBy(() -> client.callTool("get_my_profile", Map.of()))
                .isInstanceOf(McpClientException.class);

        // isSessionValid will also call get_my_profile and get the same error
        assertThat(client.isSessionValid()).isFalse();
    }

    @Test
    @DisplayName("Should increment request ID for each call")
    void shouldIncrementRequestId() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("result", result);

        stubFor(post("/mcp")
                .willReturn(okJson(objectMapper.writeValueAsString(response))));

        client.callTool("tool1", Map.of());
        client.callTool("tool2", Map.of());

        verify(2, postRequestedFor(urlEqualTo("/mcp")));
    }

    @Test
    @DisplayName("isSessionValid returns true on successful probe")
    void isSessionValidReturnsTrueOnSuccess() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("name", "User");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("result", result);

        stubFor(post("/mcp")
                .willReturn(okJson(objectMapper.writeValueAsString(response))));

        assertThat(client.isSessionValid()).isTrue();
    }

    @Test
    @DisplayName("Should retry on server error with backoff")
    void shouldRetryOnServerError() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", true);

        ObjectNode successResponse = objectMapper.createObjectNode();
        successResponse.put("jsonrpc", "2.0");
        successResponse.put("id", 1);
        successResponse.set("result", result);

        // First call fails with 500, second succeeds
        stubFor(post("/mcp")
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(serverError())
                .willSetStateTo("retried"));

        stubFor(post("/mcp")
                .inScenario("retry")
                .whenScenarioStateIs("retried")
                .willReturn(okJson(objectMapper.writeValueAsString(successResponse))));

        JsonNode actual = client.callTool("some_tool", Map.of());
        assertThat(actual.get("ok").asBoolean()).isTrue();
    }

    /**
     * No-op rate limiter for testing - always allows.
     */
    static class NoOpRateLimiter implements LinkedInRateLimiter {
        @Override
        public boolean acquire(ToolCategory category) { return true; }
        @Override
        public boolean acquireOrWait(ToolCategory category, Duration maxWait) { return true; }
        @Override
        public int getRemainingTokens(ToolCategory category) { return 100; }
    }
}
