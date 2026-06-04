package dev.jobhub.config;

import dev.jobhub.ai.AiProvider;
import dev.jobhub.ai.AnthropicProvider;
import dev.jobhub.ai.OpenAiProvider;
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

    @Bean
    public WebClient aiWebClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Bean
    public AiProvider aiProvider(WebClient aiWebClient) {
        return switch (provider.toLowerCase()) {
            case "openai" -> {
                log.info("Using OpenAI provider with extraction={}, tailoring={}", extractionModel, tailoringModel);
                yield new OpenAiProvider(aiWebClient, apiKey, extractionModel, tailoringModel);
            }
            default -> {
                log.info("Using Anthropic provider with baseUrl={}, extraction={}, tailoring={}", baseUrl, extractionModel, tailoringModel);
                yield new AnthropicProvider(aiWebClient, apiKey, baseUrl, extractionModel, tailoringModel);
            }
        };
    }
}
