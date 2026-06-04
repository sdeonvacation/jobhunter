package dev.jobhub.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Anthropic Messages API provider.
 * Uses tool_use for structured extraction and standard messages for generation.
 */
@Slf4j
public class AnthropicProvider implements AiProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;
    private final String apiKey;
    private final String baseUrl;
    private final String extractionModel;
    private final String tailoringModel;
    private final ObjectMapper objectMapper;

    public AnthropicProvider(WebClient webClient, String apiKey, String baseUrl,
                             String extractionModel, String tailoringModel) {
        this.webClient = webClient;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.anthropic.com/v1/messages";
        this.extractionModel = extractionModel;
        this.tailoringModel = tailoringModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public <T> T extract(String systemPrompt, String content, Class<T> outputType) {
        ObjectNode requestBody = buildExtractionRequest(systemPrompt, content, outputType);

        String response = executeRequest(requestBody);
        return parseToolUseResponse(response, outputType);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", tailoringModel);
        requestBody.put("max_tokens", 4096);
        requestBody.put("system", systemPrompt);

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        String response = executeRequest(requestBody);
        return parseTextResponse(response);
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    private <T> ObjectNode buildExtractionRequest(String systemPrompt, String content, Class<T> outputType) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", extractionModel);
        requestBody.put("max_tokens", 4096);
        requestBody.put("system", systemPrompt);

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        // Force tool use for structured output
        ArrayNode tools = requestBody.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", "extract_data");
        tool.put("description", "Extract structured data from the content");
        tool.set("input_schema", generateJsonSchema(outputType));

        ObjectNode toolChoice = requestBody.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", "extract_data");

        return requestBody;
    }

    private String executeRequest(ObjectNode requestBody) {
        return webClient.post()
                .uri(baseUrl)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody.toString())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new AiProviderException("Anthropic 4xx: " + body)))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new AiProviderException("Anthropic 5xx: " + body)))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                        .filter(this::isRetryable))
                .timeout(TIMEOUT)
                .block();
    }

    private <T> T parseToolUseResponse(String response, Class<T> outputType) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArray = root.get("content");
            if (contentArray != null && contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        JsonNode input = block.get("input");
                        return objectMapper.treeToValue(input, outputType);
                    }
                }
            }
            throw new AiProviderException("No tool_use block in Anthropic response");
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Failed to parse Anthropic extraction response", e);
        }
    }

    private String parseTextResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArray = root.get("content");
            if (contentArray != null && contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        return block.get("text").asText();
                    }
                }
            }
            throw new AiProviderException("No text block in Anthropic response");
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Failed to parse Anthropic text response", e);
        }
    }

    private ObjectNode generateJsonSchema(Class<?> type) {
        // Simplified schema generation for known extraction types
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        if (SkillExtractionResponse.class.equals(type)) {
            ObjectNode properties = schema.putObject("properties");
            ObjectNode skills = properties.putObject("skills");
            skills.put("type", "array");
            ObjectNode items = skills.putObject("items");
            items.put("type", "object");
            ObjectNode itemProps = items.putObject("properties");
            itemProps.putObject("name").put("type", "string");
            itemProps.putObject("category").put("type", "string");
            itemProps.putObject("required").put("type", "boolean");
            itemProps.putObject("rawMention").put("type", "string");
            ArrayNode required = items.putArray("required");
            required.add("name").add("category").add("required").add("rawMention");
            schema.putArray("required").add("skills");
        } else if (RecruiterExtraction.class.equals(type)) {
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("name").put("type", "string");
            properties.putObject("email").put("type", "string");
        }

        return schema;
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof AiProviderException ape) {
            String msg = ape.getMessage();
            return msg != null && (msg.contains("5xx") || msg.contains("529") || msg.contains("rate"));
        }
        return false;
    }
}
