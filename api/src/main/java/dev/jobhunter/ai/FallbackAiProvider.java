package dev.jobhunter.ai;

import lombok.extern.slf4j.Slf4j;

/**
 * Decorator that tries a primary provider and falls back to a secondary on any exception.
 * isAvailable() returns true if either provider is available.
 */
@Slf4j
public class FallbackAiProvider implements AiProvider {

    private final AiProvider primary;
    private final AiProvider fallback;

    public FallbackAiProvider(AiProvider primary, AiProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public <T> T extract(String systemPrompt, String content, Class<T> outputType) {
        if (primary.isAvailable()) {
            try {
                return primary.extract(systemPrompt, content, outputType);
            } catch (Exception e) {
                log.warn("Primary AI provider [{}] failed for extract, trying fallback [{}]: {}",
                        primary.name(), fallback.name(), e.getMessage());
            }
        }
        return fallback.extract(systemPrompt, content, outputType);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        if (primary.isAvailable()) {
            try {
                return primary.generate(systemPrompt, userPrompt);
            } catch (Exception e) {
                log.warn("Primary AI provider [{}] failed for generate, trying fallback [{}]: {}",
                        primary.name(), fallback.name(), e.getMessage());
            }
        }
        return fallback.generate(systemPrompt, userPrompt);
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || fallback.isAvailable();
    }

    @Override
    public String name() {
        return primary.name() + "->" + fallback.name();
    }
}
