package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisaJobsTokenProviderTest {

    private static final String APIKEY = "test-apikey";

    private WireMockServer wireMockServer;
    private VisaJobsTokenProvider provider;
    private String authUrl;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        int port = wireMockServer.port();
        String baseUrl = "http://localhost:" + port;
        authUrl = baseUrl + "/auth/v1/token";
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        provider = new VisaJobsTokenProvider(webClient, "test-refresh-token");
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private void stubAuthOk() {
        wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                .withQueryParam("grant_type", equalTo("refresh_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-token\",\"expires_in\":3600}")));
    }

    @Test
    @DisplayName("lazy obtain: no cached token → auth POST called once, returns token")
    void lazyObtain() {
        stubAuthOk();

        String token = provider.getAccessToken(authUrl, APIKEY);

        assertThat(token).isEqualTo("test-token");
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/auth/v1/token")));
    }

    @Test
    @DisplayName("cached reuse: two calls → auth POST called once, same token")
    void cachedReuse() {
        stubAuthOk();

        String first = provider.getAccessToken(authUrl, APIKEY);
        String second = provider.getAccessToken(authUrl, APIKEY);

        assertThat(first).isEqualTo("test-token");
        assertThat(second).isEqualTo(first);
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/auth/v1/token")));
    }

    @Test
    @DisplayName("invalidate() forces a second auth POST (refresh path)")
    void invalidateForcesRefresh() {
        stubAuthOk();

        provider.getAccessToken(authUrl, APIKEY);
        provider.invalidate();
        String token = provider.getAccessToken(authUrl, APIKEY);

        assertThat(token).isEqualTo("test-token");
        wireMockServer.verify(2, postRequestedFor(urlPathEqualTo("/auth/v1/token")));
    }

    @Test
    @DisplayName("refresh POST carries the Supabase apikey header")
    void sendsApikeyHeader() {
        stubAuthOk();

        provider.getAccessToken(authUrl, APIKEY);

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/auth/v1/token"))
                .withHeader("apikey", equalTo(APIKEY)));
    }

    @Test
    @DisplayName("blank apikey → throws VisaJobsRefreshTokenException mentioning apikey")
    void blankApikey() {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMockServer.port()).build();
        VisaJobsTokenProvider provider = new VisaJobsTokenProvider(webClient, "test-refresh-token");

        assertThatThrownBy(() -> provider.getAccessToken(authUrl, ""))
                .isInstanceOf(VisaJobsRefreshTokenException.class)
                .hasMessageContaining("apikey");
    }

    @Test
    @DisplayName("refresh failure (auth POST 400) → throws VisaJobsRefreshTokenException")
    void refreshFailure() {
        wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                .withQueryParam("grant_type", equalTo("refresh_token"))
                .willReturn(aResponse().withStatus(400).withBody("bad")));

        assertThatThrownBy(() -> provider.getAccessToken(authUrl, APIKEY))
                .isInstanceOf(VisaJobsRefreshTokenException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("blank refresh token → throws VisaJobsRefreshTokenException mentioning VISAJOBS_REFRESH_TOKEN")
    void blankRefreshToken() {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMockServer.port()).build();
        VisaJobsTokenProvider blankProvider = new VisaJobsTokenProvider(webClient, "");

        assertThatThrownBy(() -> blankProvider.getAccessToken(authUrl, APIKEY))
                .isInstanceOf(VisaJobsRefreshTokenException.class)
                .hasMessageContaining("VISAJOBS_REFRESH_TOKEN");
    }

    @Test
    @DisplayName("refresh response missing access_token → throws VisaJobsRefreshTokenException")
    void missingAccessToken() {
        wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                .withQueryParam("grant_type", equalTo("refresh_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"expires_in\":3600}")));

        assertThatThrownBy(() -> provider.getAccessToken(authUrl, APIKEY))
                .isInstanceOf(VisaJobsRefreshTokenException.class)
                .hasMessageContaining("access_token");
    }

    // --- refresh-token rotation persistence ---

    private WebClient wireMockWebClient() {
        return WebClient.builder().baseUrl("http://localhost:" + wireMockServer.port()).build();
    }

    @Test
    @DisplayName("rotated refresh_token is persisted to the token file and reused next time")
    void persistsRotatedRefreshToken() throws IOException {
        Path tokenFile = tempDir.resolve("visajobs_refresh_token");
        wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                .withQueryParam("grant_type", equalTo("refresh_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-1\",\"refresh_token\":\"rotated-rt\",\"expires_in\":3600}")));

        VisaJobsTokenProvider rotating =
                new VisaJobsTokenProvider(wireMockWebClient(), "seed-rt", tokenFile.toString());

        assertThat(rotating.getAccessToken(authUrl, APIKEY)).isEqualTo("tok-1");
        assertThat(Files.readString(tokenFile).strip()).isEqualTo("rotated-rt");

        // Next refresh (after invalidate) must present the rotated token, not the seed.
        rotating.invalidate();
        rotating.getAccessToken(authUrl, APIKEY);
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/auth/v1/token"))
                .withRequestBody(containing("\"refresh_token\":\"rotated-rt\"")));
    }

    @Test
    @DisplayName("persisted token file takes precedence over the env seed")
    void persistedTokenWinsOverEnv() throws IOException {
        Path tokenFile = tempDir.resolve("visajobs_refresh_token");
        Files.writeString(tokenFile, "from-file\n");
        wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                .withQueryParam("grant_type", equalTo("refresh_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-1\",\"expires_in\":3600}")));

        VisaJobsTokenProvider fromFile =
                new VisaJobsTokenProvider(wireMockWebClient(), "from-env", tokenFile.toString());

        fromFile.getAccessToken(authUrl, APIKEY);

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/auth/v1/token"))
                .withRequestBody(containing("\"refresh_token\":\"from-file\"")));
    }

    @Test
    @DisplayName("blank file and blank env token → throws mentioning VISAJOBS_REFRESH_TOKEN")
    void blankFileAndEnvToken() {
        Path tokenFile = tempDir.resolve("missing-token-file");
        VisaJobsTokenProvider noToken =
                new VisaJobsTokenProvider(wireMockWebClient(), "", tokenFile.toString());

        assertThatThrownBy(() -> noToken.getAccessToken(authUrl, APIKEY))
                .isInstanceOf(VisaJobsRefreshTokenException.class)
                .hasMessageContaining("VISAJOBS_REFRESH_TOKEN");
    }
}
