package dev.jobhunter.linkedin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linkedin-mcp")
public record LinkedInMcpProperties(
        boolean enabled,
        String baseUrl,
        String path,
        int timeoutSeconds,
        RateLimitConfig rateLimit,
        CircuitBreakerConfig circuitBreaker,
        EnrichmentConfig enrichment,
        RecruiterPostCheckConfig recruiterPostCheck
) {
    public LinkedInMcpProperties {
        if (baseUrl == null) baseUrl = "http://linkedin-mcp:8000";
        if (path == null) path = "/mcp";
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (rateLimit == null) rateLimit = new RateLimitConfig(20, 15, 10, 50);
        if (circuitBreaker == null) circuitBreaker = new CircuitBreakerConfig(5, 15);
        if (enrichment == null) enrichment = new EnrichmentConfig(false, 10, 3000);
        if (recruiterPostCheck == null) recruiterPostCheck = new RecruiterPostCheckConfig(true, 6, 7, true, "all", new AutomatedConfig(true, 10, 40, true));
    }

    public record RateLimitConfig(int searchPerHour, int profilePerHour, int actionPerHour, int totalPerHour) {}

    public record CircuitBreakerConfig(int failureThreshold, int cooldownMinutes) {}

    public record EnrichmentConfig(boolean enabled, int batchSize, int delayBetweenMs) {}

    public record RecruiterPostCheckConfig(boolean enabled, int maxCalls, int ttlDays, boolean aiVerificationEnabled, String recencyWindow, AutomatedConfig automated) {}

    public record AutomatedConfig(boolean enabled, int topN, int dailyCallBudget, boolean slimMode) {}
}
