package dev.jobhunter.strategy.aggregator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Holds the GlobalMove (the-global-move) Laravel session cookie captured once
 * by {@code scripts/globalmove-login.sh} and supplied via the
 * {@code GLOBALMOVE_SESSION_COOKIE} environment variable. The cookie is never
 * written to YAML or the database.
 *
 * <p>The session is sliding (~2h), so a Quartz keepalive calls {@link #ping()}
 * to replay the cookie against {@code GET /jobs/counts} and rotate the
 * in-memory value from any re-issued {@code Set-Cookie} header. A definitive
 * auth failure transitions the manager to {@link State#DEAD}, which surfaces
 * as {@code PROTECTED} at the strategy layer.
 *
 * <p>Only {@code the-global-move-session} is ever sent; the captured
 * {@code XSRF-TOKEN} is retained for completeness but is not sent on GETs.
 */
@Slf4j
@Component
public class GlobalMoveSessionManager {

    static final String SESSION_COOKIE_NAME = "the-global-move-session";
    static final String XSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String KEEPALIVE_PATH = "/jobs/counts";
    private static final String DEFAULT_BASE_URL = "https://globalmove.relocate.me";
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(30);

    // Login-page discriminators. The shared WebClient follows redirects, so a
    // dead session yields a 200 sign-in page rather than a 302.
    private static final List<String> LOGIN_PAGE_MARKERS = List.of(
            "subscriber sign-in",
            "auth/login");

    public enum State {
        UNCONFIGURED,
        VALID,
        DEAD
    }

    private final WebClient webClient;
    private final String envSessionCookie;
    private final String xsrfToken;
    private final String keepaliveUrl;

    private volatile String currentSessionCookie;
    private volatile State state;

    public GlobalMoveSessionManager(WebClient webClient,
                                    @Value("${GLOBALMOVE_SESSION_COOKIE:}") String sessionCookie,
                                    @Value("${GLOBALMOVE_XSRF_TOKEN:}") String xsrfToken,
                                    @Value("${globalmove.base-url:https://globalmove.relocate.me}") String baseUrl) {
        this.webClient = webClient;
        this.envSessionCookie = sessionCookie;
        this.xsrfToken = xsrfToken;
        this.keepaliveUrl = normalizeBaseUrl(baseUrl) + KEEPALIVE_PATH;

        if (isBlank(sessionCookie)) {
            this.state = State.UNCONFIGURED;
            this.currentSessionCookie = null;
            log.info("GlobalMove session not configured (GLOBALMOVE_SESSION_COOKIE unset); keepalive disabled");
        } else {
            this.state = State.VALID;
            this.currentSessionCookie = sessionCookie;
            log.info("GlobalMove session configured (xsrf token {})",
                    isBlank(xsrfToken) ? "absent" : "present");
        }
    }

    public boolean isConfigured() {
        return !isBlank(envSessionCookie);
    }

    public boolean isValid() {
        return isConfigured() && state != State.DEAD;
    }

    public String getSessionCookie() {
        if (!isConfigured()) {
            throw new GlobalMoveSessionException("GLOBALMOVE_SESSION_COOKIE is not set");
        }
        if (state == State.DEAD) {
            throw new GlobalMoveSessionException(
                    "GlobalMove session is dead; re-run scripts/globalmove-login.sh and restart the API");
        }
        String cookie = currentSessionCookie;
        if (isBlank(cookie)) {
            throw new GlobalMoveSessionException("GlobalMove session cookie is blank");
        }
        return cookie;
    }

    public void markDead() {
        if (state != State.DEAD) {
            log.warn("GlobalMove session marked DEAD; operator re-capture required");
        }
        state = State.DEAD;
    }

    public void ping() {
        if (!isConfigured()) {
            log.debug("GlobalMove keepalive skipped: session not configured");
            return;
        }

        String cookie = currentSessionCookie;
        if (isBlank(cookie)) {
            markDead();
            return;
        }

        PingOutcome outcome;
        try {
            outcome = webClient.get()
                    .uri(keepaliveUrl)
                    .header(HttpHeaders.COOKIE, SESSION_COOKIE_NAME + "=" + cookie)
                    .exchangeToMono(this::consumeResponse)
                    .block(PING_TIMEOUT);
        } catch (Exception e) {
            // Transient transport/filter failure (timeouts, exhausted 5xx/429 retries):
            // never declare the session dead on an inconclusive result.
            log.warn("GlobalMove keepalive ping failed transiently: {}", e.getMessage());
            return;
        }

        if (outcome == null) {
            log.warn("GlobalMove keepalive ping produced no response; session state unchanged");
            return;
        }

        int status = outcome.statusCode();
        if (status == 401 || status == 403) {
            log.warn("GlobalMove keepalive ping returned HTTP {}; session is dead", status);
            markDead();
            return;
        }
        if (status < 200 || status >= 300) {
            log.warn("GlobalMove keepalive ping returned HTTP {}; treating as transient", status);
            return;
        }
        if (looksLikeLoginPage(outcome.body())) {
            log.warn("GlobalMove keepalive ping returned a login page; session is dead");
            markDead();
            return;
        }

        if (!isBlank(outcome.rotatedCookie()) && !outcome.rotatedCookie().equals(cookie)) {
            log.info("GlobalMove session cookie rotated by server");
            currentSessionCookie = outcome.rotatedCookie();
        }
        state = State.VALID;
    }

    /**
     * Consumes the response inside the exchange callback (required by
     * {@code exchangeToMono}) while capturing the status and any rotated
     * session cookie.
     */
    private Mono<PingOutcome> consumeResponse(ClientResponse response) {
        int status = response.statusCode().value();
        ResponseCookie rotated = response.cookies().getFirst(SESSION_COOKIE_NAME);
        String rotatedValue = rotated != null ? rotated.getValue() : null;

        if (status >= 200 && status < 300) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> new PingOutcome(status, rotatedValue, body));
        }
        return response.releaseBody()
                .thenReturn(new PingOutcome(status, rotatedValue, ""));
    }

    private static boolean looksLikeLoginPage(String body) {
        if (isBlank(body)) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : LOGIN_PAGE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (isBlank(baseUrl)) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PingOutcome(int statusCode, String rotatedCookie, String body) {
    }
}
