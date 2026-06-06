package dev.jobhub.linkedin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linkedin-mcp")
public record LinkedInMcpProperties(
        boolean enabled,
        String baseUrl,
        String path,
        int timeoutSeconds,
        RateLimitConfig rateLimit,
        CircuitBreakerConfig circuitBreaker,
        EnrichmentConfig enrichment
) {
    public LinkedInMcpProperties {
        if (baseUrl == null) baseUrl = "http://linkedin-mcp:8000";
        if (path == null) path = "/mcp";
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (rateLimit == null) rateLimit = new RateLimitConfig(20, 15, 10, 50);
        if (circuitBreaker == null) circuitBreaker = new CircuitBreakerConfig(5, 15);
        if (enrichment == null) enrichment = new EnrichmentConfig(false, 10, 3000);
    }

    public record RateLimitConfig(int searchPerHour, int profilePerHour, int actionPerHour, int totalPerHour) {}

    public record CircuitBreakerConfig(int failureThreshold, int cooldownMinutes) {}

    public record EnrichmentConfig(boolean enabled, int batchSize, int delayBetweenMs) {}
}
