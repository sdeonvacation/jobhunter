package dev.jobhunter.linkedin;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInHealthIndicator implements HealthIndicator {

    private final HttpMcpClient mcpClient;
    private final LinkedInRateLimiter rateLimiter;

    public LinkedInHealthIndicator(HttpMcpClient mcpClient, LinkedInRateLimiter rateLimiter) {
        this.mcpClient = mcpClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Health health() {
        if (!mcpClient.isSessionValid()) {
            return Health.down()
                    .withDetail("reason", "session_expired")
                    .build();
        }

        return Health.up()
                .withDetail("search_tokens_remaining", rateLimiter.getRemainingTokens(ToolCategory.SEARCH))
                .withDetail("profile_tokens_remaining", rateLimiter.getRemainingTokens(ToolCategory.PROFILE))
                .withDetail("action_tokens_remaining", rateLimiter.getRemainingTokens(ToolCategory.ACTION))
                .build();
    }
}
