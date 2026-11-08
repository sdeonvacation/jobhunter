package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the GlobalMove (the-global-move) Laravel session cookie captured once
 * by {@code scripts/globalmove-login.sh} and supplied via the
 * {@code GLOBALMOVE_SESSION_COOKIE} environment variable or a cookie file
 * (default {@code /tmp/jobhunter/globalmove-cookie}). The cookie is never
 * written to YAML or the database.
 *
 * <p>The session is sliding (~2h), so a Quartz keepalive calls {@link #ping()}
 * to replay the cookie against {@code GET /jobs/counts}. A rotated value from
 * any re-issued {@code Set-Cookie} header replaces the in-memory cookie and is
 * persisted atomically to the cookie file, so a restart reloads the rotated
 * value instead of the stale env capture. {@link #ping()} also reloads the file
 * first, letting an operator drop a fresh cookie to recover without a restart.
 *
 * <p>Auth failures (login page, HTTP 401/403) are counted, not sticky: only
 * {@link #AUTH_FAILURE_THRESHOLD} consecutive failures transition the manager
 * to {@link State#DEAD}, which surfaces as {@code PROTECTED} at the strategy
 * layer. A successful ping resets the counter; a newer cookie in the file
 * self-heals a DEAD session.
 *
 * <p>Only {@code the-global-move-session} is ever sent; the captured
 * {@code XSRF-TOKEN} is retained for completeness but is not sent on GETs.
 * Cookie values are never logged.
 */
@Slf4j
@Component
public class GlobalMoveSessionManager {

    static final String SESSION_COOKIE_NAME = "the-global-move-session";
    static final String XSRF_COOKIE_NAME = "XSRF-TOKEN";
    /** Consecutive auth failures required before the session is declared DEAD. */
    static final int AUTH_FAILURE_THRESHOLD = 3;
    static final String DEFAULT_COOKIE_FILE = "/tmp/jobhunter/globalmove-cookie";
    static final String COOKIE_SOURCE_FILE = "file";
    static final String COOKIE_SOURCE_ENV = "env";
    static final String COOKIE_SOURCE_NONE = "none";

    private static final String KEEPALIVE_PATH = "/jobs/counts";
    private static final String DEFAULT_BASE_URL = "https://globalmove.relocate.me";
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(30);

    // Login-page discriminators. The shared WebClient follows redirects, so a
    // dead session yields a 200 sign-in page rather than a 302. The Inertia
    // payload JSON-escapes the component path ("auth\/login"), so both the
    // escaped and unescaped forms are matched.
    private static final List<String> LOGIN_PAGE_MARKERS = List.of(
            "subscriber sign-in",
            "auth/login",
            "auth\\/login");

    /** Reads the Inertia payload; {@code ObjectMapper} is thread-safe for reads. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public enum State {
        UNCONFIGURED,
        VALID,
        DEAD
    }

    private final WebClient webClient;
    private final String envSessionCookie;
    private final String xsrfToken;
    private final String keepaliveUrl;
    private final String cookieFilePath;
    private final AtomicInteger consecutiveAuthFailures = new AtomicInteger();

    private volatile String currentSessionCookie;
    private volatile State state;
    private volatile String cookieSource;

    public GlobalMoveSessionManager(WebClient webClient,
                                    @Value("${GLOBALMOVE_SESSION_COOKIE:}") String sessionCookie,
                                    @Value("${GLOBALMOVE_XSRF_TOKEN:}") String xsrfToken,
                                    @Value("${globalmove.base-url:https://globalmove.relocate.me}") String baseUrl,
                                    @Value("${globalmove.cookie-file:" + DEFAULT_COOKIE_FILE + "}") String cookieFilePath) {
        this.webClient = webClient;
        this.envSessionCookie = sessionCookie;
        this.xsrfToken = xsrfToken;
        this.keepaliveUrl = normalizeBaseUrl(baseUrl) + KEEPALIVE_PATH;
        this.cookieFilePath = isBlank(cookieFilePath) ? DEFAULT_COOKIE_FILE : cookieFilePath.trim();

        String fileCookie = readCookieFile();
        if (!isBlank(fileCookie)) {
            this.currentSessionCookie = fileCookie;
            this.state = State.VALID;
            this.cookieSource = COOKIE_SOURCE_FILE;
            log.info("GlobalMove session loaded from cookie file {} (xsrf token {})",
                    this.cookieFilePath, isBlank(xsrfToken) ? "absent" : "present");
        } else if (!isBlank(sessionCookie)) {
            this.currentSessionCookie = sessionCookie;
            this.state = State.VALID;
            this.cookieSource = COOKIE_SOURCE_ENV;
            log.info("GlobalMove session configured from env (xsrf token {})",
                    isBlank(xsrfToken) ? "absent" : "present");
        } else {
            this.currentSessionCookie = null;
            this.state = State.UNCONFIGURED;
            this.cookieSource = COOKIE_SOURCE_NONE;
            log.info("GlobalMove session not configured (GLOBALMOVE_SESSION_COOKIE unset, no cookie file); keepalive disabled");
        }
    }

    public boolean isConfigured() {
        return !isBlank(envSessionCookie) || !isBlank(currentSessionCookie);
    }

    public boolean isValid() {
        return isConfigured() && state != State.DEAD;
    }

    public String getSessionCookie() {
        if (!isConfigured()) {
            throw new GlobalMoveSessionException("GLOBALMOVE_SESSION_COOKIE is not set");
        }
        if (state == State.DEAD && !reloadCookieFromFile()) {
            throw new GlobalMoveSessionException(
                    "GlobalMove session is dead; re-run scripts/globalmove-login.sh and write the new cookie to "
                            + cookieFilePath + " (no API restart needed)");
        }
        String cookie = currentSessionCookie;
        if (isBlank(cookie)) {
            throw new GlobalMoveSessionException("GlobalMove session cookie is blank");
        }
        return cookie;
    }

    /** Force-declares the session dead regardless of the failure threshold. */
    public void markDead() {
        if (state != State.DEAD) {
            log.warn("GlobalMove session marked DEAD; operator re-capture required");
        }
        state = State.DEAD;
    }

    /**
     * Records one definitive auth failure (login page, HTTP 401/403). The
     * session only transitions to DEAD once {@link #AUTH_FAILURE_THRESHOLD}
     * consecutive failures are observed; below that it stays usable so a
     * transient rejection does not sticky-kill the session until restart.
     */
    public void recordAuthFailure(String reason) {
        if (state == State.UNCONFIGURED) {
            return;
        }
        int failures = consecutiveAuthFailures.incrementAndGet();
        if (failures >= AUTH_FAILURE_THRESHOLD) {
            if (state != State.DEAD) {
                log.warn("GlobalMove session marked DEAD after {} consecutive auth failures ({}); "
                        + "operator re-capture required", failures, reason);
            }
            state = State.DEAD;
        } else {
            log.warn("GlobalMove auth failure {}/{}: {}; session still usable",
                    failures, AUTH_FAILURE_THRESHOLD, reason);
        }
    }

    public void ping() {
        if (!isConfigured()) {
            log.debug("GlobalMove keepalive skipped: session not configured");
            return;
        }

        reloadCookieFromFile();

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
            log.warn("GlobalMove keepalive ping returned HTTP {}", status);
            recordAuthFailure("keepalive HTTP " + status);
            return;
        }
        if (status < 200 || status >= 300) {
            log.warn("GlobalMove keepalive ping returned HTTP {}; treating as transient", status);
            return;
        }
        if (looksLikeLoginPage(outcome.body())) {
            log.warn("GlobalMove keepalive ping returned a login page");
            recordAuthFailure("keepalive login page");
            return;
        }

        if (!isBlank(outcome.rotatedCookie()) && !outcome.rotatedCookie().equals(cookie)) {
            log.info("GlobalMove session cookie rotated by server");
            currentSessionCookie = outcome.rotatedCookie();
            saveCookieToFile(outcome.rotatedCookie());
        }
        consecutiveAuthFailures.set(0);
        state = State.VALID;
    }

    /** State name for health reporting (never carries the cookie value). */
    public String getStateName() {
        return state.name();
    }

    public int getConsecutiveAuthFailures() {
        return consecutiveAuthFailures.get();
    }

    /** {@code "file"}, {@code "env"} or {@code "none"} — never the cookie value. */
    public String getCookieSource() {
        return cookieSource;
    }

    /**
     * Adopts the cookie file's value when it differs from the in-memory one,
     * clearing any DEAD state so an operator can drop a fresh capture without
     * restarting the API.
     *
     * @return {@code true} when a newer file cookie was adopted
     */
    private boolean reloadCookieFromFile() {
        String fileCookie = readCookieFile();
        if (isBlank(fileCookie) || fileCookie.equals(currentSessionCookie)) {
            return false;
        }
        currentSessionCookie = fileCookie;
        consecutiveAuthFailures.set(0);
        state = State.VALID;
        cookieSource = COOKIE_SOURCE_FILE;
        log.info("reloaded GlobalMove session cookie from file");
        return true;
    }

    /** Never logs the cookie value. Returns {@code null} when absent/blank. */
    private String readCookieFile() {
        try {
            Path path = Paths.get(cookieFilePath);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            log.warn("Failed to read GlobalMove cookie file {}: {}", cookieFilePath, e.getMessage());
            return null;
        }
    }

    /**
     * Persists the rotated cookie atomically (temp file in the same directory
     * + move). Disk failures never fail a ping: the in-memory value is kept.
     */
    private void saveCookieToFile(String value) {
        Path target = Paths.get(cookieFilePath);
        Path tmp = null;
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = Files.createTempFile(parent, "gm-cookie-", ".tmp");
            Files.writeString(tmp, value, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            cookieSource = COOKIE_SOURCE_FILE;
        } catch (Exception e) {
            log.warn("Failed to persist GlobalMove session cookie to {}: {}",
                    cookieFilePath, e.getMessage());
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // best-effort cleanup of the temp file
                }
            }
        }
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

    static boolean looksLikeLoginPage(String body) {
        if (isBlank(body)) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : LOGIN_PAGE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        if (!lower.contains("data-page")) {
            return false;
        }
        String component = inertiaComponent(body);
        return component != null && component.toLowerCase(Locale.ROOT).contains("login");
    }

    /**
     * Extracts the Inertia {@code component} field from
     * {@code <script data-page="app">}, mirroring the strategy's parser.
     * Returns {@code null} when the payload is absent or unparseable, in which
     * case the caller falls back to the raw literal markers.
     */
    private static String inertiaComponent(String body) {
        try {
            Element script = Jsoup.parse(body).selectFirst("script[data-page=app]");
            if (script == null) {
                return null;
            }
            String json = script.data();
            if (isBlank(json)) {
                json = script.html();
            }
            JsonNode root = OBJECT_MAPPER.readTree(json);
            return root.path("component").asText("");
        } catch (Exception e) {
            return null;
        }
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
