package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface HttpMcpClient {

    JsonNode callTool(String toolName, Map<String, Object> params);

    boolean isSessionValid();
}
