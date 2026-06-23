package dev.jobhunter.config;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.ai.AnthropicProvider;
import dev.jobhunter.ai.FallbackAiProvider;
import dev.jobhunter.ai.OpenAiProvider;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    private String provider = "anthropic";
    private String apiKey = "";
    private String baseUrl = "";
    private String extractionModel = "claude-haiku-4-5";
    private String tailoringModel = "claude-sonnet-4-5";

    private FallbackConfig fallback;

    @Getter
    @Setter
    public static class FallbackConfig {
        private String provider = "openai";
        private String apiKey = "";
        private String baseUrl = "";
        private String extractionModel = "claude-haiku-4-5";
        private String tailoringModel = "claude-sonnet-4-5";
    }

    @Bean
    public WebClient aiWebClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Bean
    public AiProvider aiProvider(WebClient aiWebClient) {
        AiProvider primary = buildProvider(aiWebClient, provider, apiKey, baseUrl, extractionModel, tailoringModel);

        if (fallback != null && fallback.getApiKey() != null && !fallback.getApiKey().isBlank()) {
            AiProvider secondary = buildProvider(aiWebClient,
                    fallback.getProvider(), fallback.getApiKey(), fallback.getBaseUrl(),
                    fallback.getExtractionModel(), fallback.getTailoringModel());
            log.info("AI provider: primary=[{}], fallback=[{}]", primary.name(), secondary.name());
            return new FallbackAiProvider(primary, secondary);
        }

        log.info("AI provider: [{}] (no fallback configured)", primary.name());
        return primary;
    }

    private AiProvider buildProvider(WebClient webClient, String providerName, String key,
                                     String url, String extractModel, String tailorModel) {
        return switch (providerName.toLowerCase()) {
            case "openai" -> {
                log.debug("Building OpenAI provider: baseUrl={}, extraction={}, tailoring={}", url, extractModel, tailorModel);
                yield new OpenAiProvider(webClient, key, url, extractModel, tailorModel);
            }
            default -> {
                log.debug("Building Anthropic provider: baseUrl={}, extraction={}, tailoring={}", url, extractModel, tailorModel);
                yield new AnthropicProvider(webClient, key, url, extractModel, tailorModel);
            }
        };
    }
}
