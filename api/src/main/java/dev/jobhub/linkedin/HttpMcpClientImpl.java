package dev.jobhub.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class HttpMcpClientImpl implements HttpMcpClient {

    private static final int SESSION_EXPIRED_ERROR_CODE = -32001;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final String MCP_SESSION_HEADER = "Mcp-Session-Id";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final LinkedInRateLimiter rateLimiter;
    private final String path;
    private final AtomicBoolean sessionValid = new AtomicBoolean(true);
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);
    private volatile String mcpSessionId;

    public HttpMcpClientImpl(WebClient.Builder webClientBuilder,
                             LinkedInMcpProperties properties,
                             LinkedInRateLimiter rateLimiter,
                             ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .baseUrl(properties.baseUrl())
                .build();
        this.path = properties.path();
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode callTool(String toolName, Map<String, Object> params) {
        return callToolAsync(toolName, params).block();
    }

    @Override
    public Mono<JsonNode> callToolAsync(String toolName, Map<String, Object> params) {
        return ensureInitialized()
                .then(Mono.defer(() -> {
                    ObjectNode request = buildJsonRpcRequest(toolName, params);
                    return doPost(request);
                }))
                .flatMap(response -> {
                    if (response.has("error")) {
                        JsonNode error = response.get("error");
                        int code = error.has("code") ? error.get("code").asInt() : 0;
                        String message = error.has("message") ? error.get("message").asText() : "Unknown MCP error";

                        if (code == SESSION_EXPIRED_ERROR_CODE) {
                            sessionValid.set(false);
                            mcpSessionId = null;
                            log.warn("LinkedIn MCP session expired (error code {})", code);
                        }
                        return Mono.error(new McpClientException(message, code));
                    }
                    return Mono.just(response.get("result"));
                })
                .retryWhen(Retry.backoff(MAX_RETRY_ATTEMPTS, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(8))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal ->
                                log.debug("Retrying MCP call to '{}', attempt {}", toolName, signal.totalRetries() + 1)))
                .doOnError(ex -> log.error("MCP call to '{}' failed: {}", toolName, ex.getMessage()));
    }

    @Override
    public boolean isSessionValid() {
        try {
            callTool("get_my_profile", Map.of());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Mono<Void> ensureInitialized() {
        if (mcpSessionId != null) {
            return Mono.empty();
        }
        return initialize();
    }

    private Mono<Void> initialize() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdCounter.incrementAndGet());
        request.put("method", "initialize");

        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "jobhub");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);
        request.set("params", params);

        return webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .exchangeToMono(response -> {
                    String sessionId = response.headers().asHttpHeaders().getFirst(MCP_SESSION_HEADER);
                    if (sessionId != null) {
                        mcpSessionId = sessionId;
                        log.info("MCP session initialized: {}", sessionId);
                    }
                    return response.bodyToMono(String.class);
                })
                .doOnError(ex -> log.error("MCP initialization failed: {}", ex.getMessage()))
                .then();
    }

    private Mono<JsonNode> doPost(ObjectNode request) {
        var requestSpec = webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);

        if (mcpSessionId != null) {
            requestSpec = requestSpec.header(MCP_SESSION_HEADER, mcpSessionId);
        }

        return requestSpec
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(raw -> {
                    try {
                        return Mono.just(parseSseResponse(raw));
                    } catch (Exception e) {
                        return Mono.error(new McpClientException("Failed to parse MCP response: " + e.getMessage(), 0));
                    }
                });
    }

    private ObjectNode buildJsonRpcRequest(String toolName, Map<String, Object> params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdCounter.incrementAndGet());
        request.put("method", "tools/call");

        ObjectNode rpcParams = objectMapper.createObjectNode();
        rpcParams.put("name", toolName);
        rpcParams.set("arguments", objectMapper.valueToTree(params));
        request.set("params", rpcParams);

        return request;
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientRequestException) {
            return false;
        }
        if (throwable instanceof McpClientException mce) {
            return mce.getErrorCode() != SESSION_EXPIRED_ERROR_CODE;
        }
        if (throwable instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();
        }
        return true;
    }

    private JsonNode parseSseResponse(String raw) throws Exception {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readTree(trimmed);
        }
        String[] lines = trimmed.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("data:")) {
                String json = line.substring(5).trim();
                if (json.startsWith("{")) {
                    return objectMapper.readTree(json);
                }
            }
        }
        throw new IllegalStateException("No JSON data found in SSE response: " + trimmed.substring(0, Math.min(200, trimmed.length())));
    }
}
