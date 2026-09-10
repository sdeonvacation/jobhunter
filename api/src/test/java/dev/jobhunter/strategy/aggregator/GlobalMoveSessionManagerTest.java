package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalMoveSessionManagerTest {

    private static final String SESSION_COOKIE = "the-global-move-session";

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private GlobalMoveSessionManager newManager(String sessionCookie, String xsrfToken) {
        String baseUrl = "http://localhost:" + wireMockServer.port();
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        return new GlobalMoveSessionManager(webClient, sessionCookie, xsrfToken, baseUrl);
    }

    @Test
    @DisplayName("blank session cookie → unconfigured, invalid, getSessionCookie() throws")
    void blankSessionCookie_isUnconfigured() {
        GlobalMoveSessionManager manager = newManager("", "xsrf");

        assertThat(manager.isConfigured()).isFalse();
        assertThat(manager.isValid()).isFalse();
        assertThatThrownBy(manager::getSessionCookie)
                .isInstanceOf(GlobalMoveSessionException.class)
                .hasMessageContaining("GLOBALMOVE_SESSION_COOKIE");
    }

    @Test
    @DisplayName("non-blank session cookie → configured, getSessionCookie() returns stored value")
    void nonBlankSessionCookie_isConfigured() {
        GlobalMoveSessionManager manager = newManager("captured-cookie", "xsrf");

        assertThat(manager.isConfigured()).isTrue();
        assertThat(manager.getSessionCookie()).isEqualTo("captured-cookie");
    }

    @Test
    @DisplayName("ping() success rotates cookie from Set-Cookie header")
    void ping_success_rotatesCookie() {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs/counts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", SESSION_COOKIE + "=rotated-value; Path=/; HttpOnly")
                        .withBody("{\"count\":5}")));
        GlobalMoveSessionManager manager = newManager("original-cookie", "xsrf");

        manager.ping();

        assertThat(manager.isValid()).isTrue();
        assertThat(manager.getSessionCookie()).isEqualTo("rotated-value");
    }

    @Test
    @DisplayName("ping() HTTP 401 marks session dead")
    void ping_401_marksDead() {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs/counts"))
                .willReturn(aResponse().withStatus(401)));
        GlobalMoveSessionManager manager = newManager("original-cookie", "xsrf");

        manager.ping();

        assertThat(manager.isValid()).isFalse();
        assertThatThrownBy(manager::getSessionCookie)
                .isInstanceOf(GlobalMoveSessionException.class)
                .hasMessageContaining("dead");
    }

    @Test
    @DisplayName("ping() 200 login-page body marks session dead")
    void ping_loginPageBody_marksDead() {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs/counts"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("<html><title>Subscriber sign-in</title></html>")));
        GlobalMoveSessionManager manager = newManager("original-cookie", "xsrf");

        manager.ping();

        assertThat(manager.isValid()).isFalse();
        assertThatThrownBy(manager::getSessionCookie)
                .isInstanceOf(GlobalMoveSessionException.class)
                .hasMessageContaining("dead");
    }

    @Test
    @DisplayName("ping() HTTP 500 is transient — session stays valid")
    void ping_500_doesNotMarkDead() {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs/counts"))
                .willReturn(aResponse().withStatus(500)));
        GlobalMoveSessionManager manager = newManager("original-cookie", "xsrf");

        manager.ping();

        assertThat(manager.isValid()).isTrue();
        assertThat(manager.getSessionCookie()).isEqualTo("original-cookie");
    }

    @Test
    @DisplayName("ping() connection error is transient — session stays valid")
    void ping_connectionError_doesNotMarkDead() {
        int port = wireMockServer.port();
        wireMockServer.stop();
        wireMockServer = null;
        String baseUrl = "http://localhost:" + port;
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        GlobalMoveSessionManager manager = new GlobalMoveSessionManager(webClient, "original-cookie", "xsrf", baseUrl);

        manager.ping();

        assertThat(manager.isValid()).isTrue();
        assertThat(manager.getSessionCookie()).isEqualTo("original-cookie");
    }

    @Test
    @DisplayName("markDead() after valid → getSessionCookie() throws")
    void markDead_afterValid_throws() {
        GlobalMoveSessionManager manager = newManager("original-cookie", "xsrf");
        assertThat(manager.isValid()).isTrue();

        manager.markDead();

        assertThat(manager.isValid()).isFalse();
        assertThatThrownBy(manager::getSessionCookie)
                .isInstanceOf(GlobalMoveSessionException.class)
                .hasMessageContaining("dead");
    }

    @Test
    @DisplayName("ping() when unconfigured performs no HTTP call and does not throw")
    void ping_unconfigured_noHttpCall() {
        GlobalMoveSessionManager manager = newManager("", "xsrf");

        manager.ping();

        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/jobs/counts")));
    }
}