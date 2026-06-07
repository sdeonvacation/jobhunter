package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface HttpMcpClient {

    JsonNode callTool(String toolName, Map<String, Object> params);

    Mono<JsonNode> callToolAsync(String toolName, Map<String, Object> params);

    boolean isSessionValid();
}
