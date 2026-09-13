package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;

/**
 * Provides short-lived access tokens for the VisaJobs Supabase REST API by
 * exchanging a long-lived refresh token against the Supabase auth endpoint.
 * Access tokens are cached with a 5-minute safety margin; callers can
 * {@link #invalidate()} to force a refresh (e.g. after a 401).
 *
 * <p><b>apikey header.</b> The Supabase GoTrue {@code /token} endpoint requires
 * the project {@code apikey} on the refresh call as well as on data calls;
 * without it the request is rejected with
 * {@code 401 No API key found in request} before the refresh token is examined.
 *
 * <p><b>Refresh-token rotation.</b> The VisaJobs Supabase project has rotation
 * (and reuse detection) enabled, so each successful refresh invalidates the
 * presented refresh token and returns a new one. A token stored only in the
 * environment therefore works exactly once. To survive rotation the provider
 * seeds from {@code VISAJOBS_REFRESH_TOKEN} but persists every rotated token to
 * a local file (default {@code ~/.jobhunter/visajobs_refresh_token}, override
 * with {@code VISAJOBS_REFRESH_TOKEN_FILE}) and prefers the file on startup.
 */
@Slf4j
@Component
public class VisaJobsTokenProvider {

    private static final long CACHE_MARGIN_SECONDS = 300;
    private static final int MAX_ERROR_BODY_CHARS = 300;

    private static final Path DEFAULT_TOKEN_FILE =
            Path.of(System.getProperty("user.home"), ".jobhunter", "visajobs_refresh_token");

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Where rotated refresh tokens are persisted; {@code null} disables persistence. */
    private final Path tokenFile;

    /** Current refresh token: seeded from env, then replaced by rotated values. */
    private volatile String refreshToken;

    private volatile String cachedAccessToken;
    private volatile long cachedExpiresAtEpochSec;

    @Autowired
    public VisaJobsTokenProvider(WebClient webClient,
                                 @Value("${VISAJOBS_REFRESH_TOKEN:}") String refreshToken,
                                 @Value("${VISAJOBS_REFRESH_TOKEN_FILE:}") String refreshTokenFile) {
        this.webClient = webClient;
        this.tokenFile = (refreshTokenFile == null || refreshTokenFile.isBlank())
                ? DEFAULT_TOKEN_FILE
                : Path.of(refreshTokenFile).toAbsolutePath();
        this.refreshToken = loadInitialToken(refreshToken);
    }

    /** Convenience constructor for tests and embedded use: no rotated-token persistence. */
    public VisaJobsTokenProvider(WebClient webClient, String refreshToken) {
        this.webClient = webClient;
        this.tokenFile = null;
        this.refreshToken = refreshToken;
    }

    public String getAccessToken(String authUrl, String apikey) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new VisaJobsRefreshTokenException("VISAJOBS_REFRESH_TOKEN is not set");
        }
        if (apikey == null || apikey.isBlank()) {
            throw new VisaJobsRefreshTokenException("VisaJobs apikey is not configured");
        }
        if (cachedAccessToken != null && cachedExpiresAtEpochSec - Instant.now().getEpochSecond() > CACHE_MARGIN_SECONDS) {
            return cachedAccessToken;
        }
        return refresh(authUrl, apikey);
    }

    public void invalidate() {
        cachedAccessToken = null;
        cachedExpiresAtEpochSec = 0;
    }

    private synchronized String refresh(String authUrl, String apikey) {
        // Double-check after acquiring the lock: another thread may have refreshed already.
        if (cachedAccessToken != null && cachedExpiresAtEpochSec - Instant.now().getEpochSecond() > CACHE_MARGIN_SECONDS) {
            return cachedAccessToken;
        }

        String body = "{\"refresh_token\":\"" + refreshToken + "\"}";
        try {
            String responseBody = webClient.post()
                    .uri(authUrl + "?grant_type=refresh_token")
                    .header("Content-Type", "application/json")
                    .header("apikey", apikey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            JsonNode root = objectMapper.readTree(responseBody);
            String accessToken = root.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new VisaJobsRefreshTokenException("Refresh response missing access_token");
            }

            persistRotatedRefreshToken(root.path("refresh_token").asText(null));

            long expiresIn = root.path("expires_in").asLong(3600);
            cachedAccessToken = accessToken;
            cachedExpiresAtEpochSec = Instant.now().getEpochSecond() + expiresIn;
            return accessToken;
        } catch (WebClientResponseException e) {
            throw new VisaJobsRefreshTokenException(
                    "Refresh failed: HTTP " + e.getStatusCode().value() + " " + e.getMessage()
                            + " body=" + abbreviate(e.getResponseBodyAsString()));
        } catch (JsonProcessingException e) {
            throw new VisaJobsRefreshTokenException("Refresh response parse failed: " + e.getMessage());
        }
    }

    private String loadInitialToken(String envToken) {
        if (tokenFile != null && Files.exists(tokenFile)) {
            try {
                String fromFile = Files.readString(tokenFile).strip();
                if (!fromFile.isBlank()) {
                    log.debug("Using persisted VisaJobs refresh token from {}", tokenFile);
                    return fromFile;
                }
            } catch (IOException e) {
                log.warn("Could not read persisted VisaJobs refresh token from {}: {}", tokenFile, e.getMessage());
            }
        }
        return envToken;
    }

    /**
     * Stores a rotated refresh token so the next exchange uses it. Rotation
     * invalidates the previously presented token, so failing to persist would
     * leave the integration permanently unauthenticated after one refresh.
     */
    private void persistRotatedRefreshToken(String rotated) {
        if (rotated == null || rotated.isBlank() || rotated.equals(refreshToken)) {
            return;
        }
        refreshToken = rotated;
        if (tokenFile == null) {
            return;
        }
        try {
            Path parent = tokenFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
            Files.writeString(tmp, rotated, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX filesystem (e.g. Windows) — fall back to default permissions.
            }
            Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("Persisted rotated VisaJobs refresh token to {}", tokenFile);
        } catch (IOException e) {
            log.warn("Failed to persist rotated VisaJobs refresh token to {}: {}", tokenFile, e.getMessage());
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= MAX_ERROR_BODY_CHARS ? trimmed : trimmed.substring(0, MAX_ERROR_BODY_CHARS) + "…";
    }
}
