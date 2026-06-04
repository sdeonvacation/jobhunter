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
 * OpenAI Chat Completions API provider.
 * Uses response_format json_schema for structured extraction.
 */
@Slf4j
public class OpenAiProvider implements AiProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;
    private final String apiKey;
    private final String baseUrl;
    private final String extractionModel;
    private final String tailoringModel;
    private final ObjectMapper objectMapper;

    public OpenAiProvider(WebClient webClient, String apiKey,
                          String extractionModel, String tailoringModel) {
        this(webClient, apiKey, DEFAULT_BASE_URL, extractionModel, tailoringModel);
    }

    public OpenAiProvider(WebClient webClient, String apiKey, String baseUrl,
                          String extractionModel, String tailoringModel) {
        this.webClient = webClient;
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.extractionModel = extractionModel;
        this.tailoringModel = tailoringModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public <T> T extract(String systemPrompt, String content, Class<T> outputType) {
        ObjectNode requestBody = buildExtractionRequest(systemPrompt, content, outputType);
        String response = executeRequest(requestBody);
        return parseJsonResponse(response, outputType);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", tailoringModel);
        requestBody.put("max_tokens", 4096);

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        String response = executeRequest(requestBody);
        return parseTextResponse(response);
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "openai";
    }

    private <T> ObjectNode buildExtractionRequest(String systemPrompt, String content, Class<T> outputType) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", extractionModel);
        requestBody.put("max_tokens", 4096);

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", content);

        // Structured output via response_format
        ObjectNode responseFormat = requestBody.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "extraction_result");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", generateJsonSchema(outputType));

        return requestBody;
    }

    private String executeRequest(ObjectNode requestBody) {
        return webClient.post()
                .uri(baseUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody.toString())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new AiProviderException("OpenAI 4xx: " + body)))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new AiProviderException("OpenAI 5xx: " + body)))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                        .filter(this::isRetryable))
                .timeout(TIMEOUT)
                .block();
    }

    private <T> T parseJsonResponse(String response, Class<T> outputType) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                String content = message.get("content").asText();
                return objectMapper.readValue(content, outputType);
            }
            throw new AiProviderException("No choices in OpenAI response");
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Failed to parse OpenAI extraction response", e);
        }
    }

    private String parseTextResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).get("message").get("content").asText();
            }
            throw new AiProviderException("No choices in OpenAI response");
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Failed to parse OpenAI text response", e);
        }
    }

    private ObjectNode generateJsonSchema(Class<?> type) {
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
            ArrayNode itemRequired = items.putArray("required");
            itemRequired.add("name").add("category").add("required").add("rawMention");
            items.put("additionalProperties", false);
            schema.putArray("required").add("skills");
            schema.put("additionalProperties", false);
        } else if (RecruiterExtraction.class.equals(type)) {
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("name").put("type", "string");
            properties.putObject("email").put("type", "string");
            schema.putArray("required").add("name").add("email");
            schema.put("additionalProperties", false);
        }

        return schema;
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof AiProviderException ape) {
            String msg = ape.getMessage();
            return msg != null && (msg.contains("5xx") || msg.contains("429") || msg.contains("rate"));
        }
        return false;
    }
}
