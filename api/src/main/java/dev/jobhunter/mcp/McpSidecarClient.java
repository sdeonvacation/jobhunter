package dev.jobhunter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generic stdio JSON-RPC client for MCP sidecar processes.
 * Spawns process, writes JSON-RPC to stdin, reads from stdout.
 * Handles: process crash (restart next call), timeout, malformed response.
 */
@Slf4j
@Component
public class McpSidecarClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    /**
     * Call an MCP tool via stdio JSON-RPC.
     *
     * @param command  executable command (e.g. "npx")
     * @param args     arguments to spawn process with (e.g. ["-y", "jobspy-mcp"])
     * @param toolName MCP tool name to invoke
     * @param params   tool parameters
     * @return JSON response content node
     */
    public JsonNode callTool(String command, List<String> args, String toolName, Map<String, Object> params) {
        return callTool(command, args, toolName, params, DEFAULT_TIMEOUT);
    }

    public JsonNode callTool(String command, List<String> args, String toolName, Map<String, Object> params, Duration timeout) {
        Process process = null;
        try {
            List<String> fullCommand = new java.util.ArrayList<>();
            fullCommand.add(command);
            fullCommand.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(fullCommand)
                    .redirectErrorStream(false);
            process = pb.start();

            // Send initialize request
            int initId = requestIdCounter.incrementAndGet();
            ObjectNode initRequest = buildJsonRpcRequest(initId, "initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "jobhub", "version", "1.0.0")
            ));
            writeRequest(process.getOutputStream(), initRequest);
            readResponse(process.getInputStream(), timeout);

            // Send initialized notification
            ObjectNode initializedNotification = MAPPER.createObjectNode();
            initializedNotification.put("jsonrpc", "2.0");
            initializedNotification.put("method", "notifications/initialized");
            writeRequest(process.getOutputStream(), initializedNotification);

            // Send tools/call request
            int callId = requestIdCounter.incrementAndGet();
            ObjectNode callRequest = buildJsonRpcRequest(callId, "tools/call", Map.of(
                    "name", toolName,
                    "arguments", params
            ));
            writeRequest(process.getOutputStream(), callRequest);
            JsonNode response = readResponse(process.getInputStream(), timeout);

            if (response.has("error")) {
                throw new McpSidecarException("MCP tool error: " + response.get("error"));
            }

            return response.has("result") ? response.get("result") : response;
        } catch (McpSidecarException e) {
            throw e;
        } catch (Exception e) {
            throw new McpSidecarException("MCP sidecar call failed: " + e.getMessage(), e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private ObjectNode buildJsonRpcRequest(int id, String method, Map<String, Object> params) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", MAPPER.valueToTree(params));
        return request;
    }

    private void writeRequest(OutputStream os, JsonNode request) throws IOException {
        String json = MAPPER.writeValueAsString(request);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        os.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }

    private JsonNode readResponse(InputStream is, Duration timeout) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        // Read headers (Content-Length)
        int contentLength = -1;
        while (System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                throw new McpSidecarException("Process closed stdout unexpectedly");
            }
            if (line.isEmpty()) {
                break; // End of headers
            }
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }

        if (contentLength <= 0) {
            throw new McpSidecarException("Missing or invalid Content-Length header");
        }

        // Read body
        char[] buffer = new char[contentLength];
        int read = 0;
        while (read < contentLength && System.currentTimeMillis() < deadline) {
            int chunk = reader.read(buffer, read, contentLength - read);
            if (chunk == -1) {
                throw new McpSidecarException("Unexpected end of stream");
            }
            read += chunk;
        }

        if (read < contentLength) {
            throw new McpSidecarException("Timeout reading MCP response");
        }

        return MAPPER.readTree(new String(buffer));
    }

    public static class McpSidecarException extends RuntimeException {
        public McpSidecarException(String message) {
            super(message);
        }

        public McpSidecarException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
