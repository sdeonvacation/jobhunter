package dev.jobhunter.resolution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "resolution")
public record ResolutionProperties(
        GoogleSearchConfig googleSearch,
        List<String> strategiesOrder,
        int timeoutSeconds
) {

    public record GoogleSearchConfig(String apiKey, String cx) {}

    public ResolutionProperties {
        if (strategiesOrder == null) {
            strategiesOrder = List.of("PATTERN_MATCH", "GOOGLE_SEARCH", "REDIRECT_FOLLOW");
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 15;
        }
    }
}
