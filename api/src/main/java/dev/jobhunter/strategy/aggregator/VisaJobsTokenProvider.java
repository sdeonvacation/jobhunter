package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;

/**
 * Provides short-lived access tokens for the VisaJobs Supabase REST API by
 * exchanging a long-lived refresh token (env VISAJOBS_REFRESH_TOKEN) against
 * the Supabase auth endpoint. Access tokens are cached with a 5-minute
 * safety margin; callers can {@link #invalidate()} to force a refresh
 * (e.g. after a 401).
 */
@Slf4j
@Component
public class VisaJobsTokenProvider {

    private static final long CACHE_MARGIN_SECONDS = 300;

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String refreshToken;
    private volatile String cachedAccessToken;
    private volatile long cachedExpiresAtEpochSec;

    public VisaJobsTokenProvider(WebClient webClient,
                                 @Value("${VISAJOBS_REFRESH_TOKEN:}") String refreshToken) {
        this.webClient = webClient;
        this.refreshToken = refreshToken;
    }

    public String getAccessToken(String authUrl) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new VisaJobsRefreshTokenException("VISAJOBS_REFRESH_TOKEN is not set");
        }
        if (cachedAccessToken != null && cachedExpiresAtEpochSec - Instant.now().getEpochSecond() > CACHE_MARGIN_SECONDS) {
            return cachedAccessToken;
        }
        return refresh(authUrl);
    }

    public void invalidate() {
        cachedAccessToken = null;
        cachedExpiresAtEpochSec = 0;
    }

    private synchronized String refresh(String authUrl) {
        // Double-check after acquiring the lock: another thread may have refreshed already.
        if (cachedAccessToken != null && cachedExpiresAtEpochSec - Instant.now().getEpochSecond() > CACHE_MARGIN_SECONDS) {
            return cachedAccessToken;
        }

        String body = "{\"refresh_token\":\"" + refreshToken + "\"}";
        try {
            String responseBody = webClient.post()
                    .uri(authUrl + "?grant_type=refresh_token")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            JsonNode root = objectMapper.readTree(responseBody);
            String accessToken = root.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new VisaJobsRefreshTokenException("Refresh response missing access_token");
            }
            long expiresIn = root.path("expires_in").asLong(3600);
            cachedAccessToken = accessToken;
            cachedExpiresAtEpochSec = Instant.now().getEpochSecond() + expiresIn;
            return accessToken;
        } catch (WebClientResponseException e) {
            throw new VisaJobsRefreshTokenException(
                    "Refresh failed: HTTP " + e.getStatusCode().value() + " " + e.getMessage());
        } catch (JsonProcessingException e) {
            throw new VisaJobsRefreshTokenException("Refresh response parse failed: " + e.getMessage());
        }
    }
}