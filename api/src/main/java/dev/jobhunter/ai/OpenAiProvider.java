package dev.jobhunter.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.strategy.direct.AiExtractionResponse;
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
        AiProviderException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String response = executeRequest(requestBody);
                return parseJsonResponse(response, outputType);
            } catch (AiProviderException e) {
                if (e.getMessage().contains("Failed to parse")) {
                    lastError = e;
                    log.warn("OpenAI extraction parse failed (attempt {}), retrying", attempt + 1);
                    continue;
                }
                throw e;
            }
        }
        throw lastError;
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
        requestBody.put("max_tokens", 16384);

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
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                String content = message.get("content").asText();

                // Strip markdown fences (Gemini often wraps JSON in ```json ... ```)
                content = stripMarkdownFences(content);

                // Check if response was truncated due to token limit
                String finishReason = firstChoice.has("finish_reason")
                        ? firstChoice.get("finish_reason").asText() : "unknown";
                if ("length".equals(finishReason)) {
                    log.warn("OpenAI extraction response truncated (hit max_tokens). Attempting JSON repair.");
                }

                try {
                    return objectMapper.readValue(content, outputType);
                } catch (JsonProcessingException parseEx) {
                    // Attempt JSON repair for truncated responses
                    String repaired = attemptJsonRepair(content);
                    if (repaired != null) {
                        log.warn("Recovered partial results from truncated OpenAI response");
                        return objectMapper.readValue(repaired, outputType);
                    }
                    log.error("Failed to parse AI response. Content starts with: {}",
                            content.substring(0, Math.min(200, content.length())));
                    throw parseEx;
                }
            }
            throw new AiProviderException("No choices in OpenAI response");
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Failed to parse OpenAI extraction response", e);
        }
    }

    private String stripMarkdownFences(String content) {
        if (content == null) return null;
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            // Remove opening fence (```json or ```)
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            // Remove closing fence
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).stripTrailing();
            }
        }
        return trimmed;
    }

    private String attemptJsonRepair(String truncatedJson) {
        // Find last complete JSON object in array
        int lastCloseBrace = truncatedJson.lastIndexOf('}');
        if (lastCloseBrace <= 0) return null;

        String trimmed = truncatedJson.substring(0, lastCloseBrace + 1);

        // Count unclosed brackets to determine what needs closing
        int openBrackets = 0;
        int openBraces = 0;
        boolean inString = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '[') openBrackets++;
                else if (c == ']') openBrackets--;
                else if (c == '{') openBraces++;
                else if (c == '}') openBraces--;
            }
        }

        StringBuilder repaired = new StringBuilder(trimmed);
        for (int i = 0; i < openBrackets; i++) repaired.append(']');
        for (int i = 0; i < openBraces; i++) repaired.append('}');

        // Validate the repaired JSON parses successfully
        try {
            objectMapper.readTree(repaired.toString());
            return repaired.toString();
        } catch (JsonProcessingException e) {
            return null;
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
        } else if (AiExtractionResponse.class.equals(type)) {
            ObjectNode properties = schema.putObject("properties");
            ObjectNode jobs = properties.putObject("jobs");
            jobs.put("type", "array");
            ObjectNode items = jobs.putObject("items");
            items.put("type", "object");
            ObjectNode itemProps = items.putObject("properties");
            itemProps.putObject("title").put("type", "string");
            itemProps.putObject("location").put("type", "string");
            itemProps.putObject("applyUrl").put("type", "string");
            ArrayNode itemRequired = items.putArray("required");
            itemRequired.add("title").add("location").add("applyUrl");
            items.put("additionalProperties", false);
            schema.putArray("required").add("jobs");
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
