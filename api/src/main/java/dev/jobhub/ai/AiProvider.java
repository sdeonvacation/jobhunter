package dev.jobhub.ai;

/**
 * Abstraction over AI model providers for structured extraction and text generation.
 */
public interface AiProvider {

    /**
     * Extract structured data from content using a system prompt.
     * Implementation should use tool_use/json_schema for reliable parsing.
     */
    <T> T extract(String systemPrompt, String content, Class<T> outputType);

    /**
     * Generate free-form text from system + user prompts.
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * Check if this provider is properly configured and reachable.
     */
    boolean isAvailable();

    /**
     * Provider identifier name.
     */
    String name();
}
