package dev.jobhunter.linkedin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LinkedInHealthIndicatorTest {

    private HttpMcpClient mcpClient;
    private LinkedInRateLimiter rateLimiter;
    private LinkedInHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        mcpClient = mock(HttpMcpClient.class);
        rateLimiter = mock(LinkedInRateLimiter.class);
        healthIndicator = new LinkedInHealthIndicator(mcpClient, rateLimiter);
    }

    @Test
    @DisplayName("Should return UP with token details when session valid")
    void shouldReturnUpWhenSessionValid() {
        when(mcpClient.isSessionValid()).thenReturn(true);
        when(rateLimiter.getRemainingTokens(ToolCategory.SEARCH)).thenReturn(18);
        when(rateLimiter.getRemainingTokens(ToolCategory.PROFILE)).thenReturn(12);
        when(rateLimiter.getRemainingTokens(ToolCategory.ACTION)).thenReturn(9);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        Map<String, Object> details = health.getDetails();
        assertThat(details.get("search_tokens_remaining")).isEqualTo(18);
        assertThat(details.get("profile_tokens_remaining")).isEqualTo(12);
        assertThat(details.get("action_tokens_remaining")).isEqualTo(9);
    }

    @Test
    @DisplayName("Should return DOWN when session expired")
    void shouldReturnDownWhenSessionExpired() {
        when(mcpClient.isSessionValid()).thenReturn(false);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("session_expired");
    }
}
