package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class VisaJobsStrategyTest {

    private WireMockServer wireMockServer;
    private VisaJobsStrategy strategy;
    private VisaJobsTokenProvider tokenProvider;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        int port = wireMockServer.port();
        baseUrl = "http://localhost:" + port;
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        tokenProvider = new VisaJobsTokenProvider(webClient, "test-refresh-token");
        strategy = new VisaJobsStrategy(webClient, tokenProvider);
        stubAuth();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private void stubAuth() {
        wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                .withQueryParam("grant_type", equalTo("refresh_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-token\",\"expires_in\":3600}")));
    }

    private FetchContext context(int maxResults, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("url", baseUrl + "/rest/v1/jobs");
        config.put("apikey", "anon-key");
        config.put("auth-url", baseUrl + "/auth/v1/token");
        config.putAll(extra);
        return FetchContext.forSearch(List.of(), List.of(), maxResults, 10, config);
    }

    private String row(String jobId, String title, String company, String location,
                       String description, String url) {
        return """
                {"job_id":"%s","title":"%s","company":"%s","location":"%s","description_text":"%s","url":"%s","posted":"2026-08-25T08:55:35+00:00"}
                """.formatted(jobId, title, company, location, description, url);
    }

    private String rows(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── metadata ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("name() returns visajobs")
    void nameReturnsVisaJobs() {
        assertThat(strategy.name()).isEqualTo("visajobs");
    }

    @Test
    @DisplayName("supportedTypes() returns empty set")
    void supportedTypesEmpty() {
        assertThat(strategy.supportedTypes()).isEmpty();
    }

    @Test
    @DisplayName("supports() returns false for all AtsType values")
    void supportsReturnsFalseForAll() {
        for (AtsType type : AtsType.values()) {
            assertThat(strategy.supports(type)).isFalse();
        }
    }

    // ── fetch() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fetch()")
    class FetchTests {

        @Test
        @DisplayName("happy path single page: 3 rows → SUCCESS with correct field mapping")
        void happyPathSinglePage() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(
                            row("1", "Senior Engineer", "Acme", "Berlin", "Backend role", "https://www.arbeitnow.com/jobs/1"),
                            row("2", "Backend Dev", "Beta", "Munich", "Java role", "https://www.arbeitnow.com/jobs/2"),
                            row("3", "Data Engineer", "Gamma", "Hamburg", "Data role", "https://www.arbeitnow.com/jobs/3")))));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            RawAggregatorJob job0 = result.jobs().get(0);
            assertThat(job0.externalId()).isEqualTo("1");
            assertThat(job0.title()).isEqualTo("Senior Engineer");
            assertThat(job0.companyName()).isEqualTo("Acme");
            assertThat(job0.location()).isEqualTo("Berlin");
            assertThat(job0.description()).isEqualTo("Backend role");
            assertThat(job0.applyUrl()).isEqualTo("https://www.arbeitnow.com/jobs/1");
            assertThat(job0.postedDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 25));
        }

        @Test
        @DisplayName("request carries apikey and Authorization Bearer headers")
        void headersSent() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(row("1", "Role", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1")))));

            strategy.fetch(context(50, Map.of()));

            wireMockServer.verify(getRequestedFor(urlPathEqualTo("/rest/v1/jobs"))
                    .withHeader("apikey", equalTo("anon-key"))
                    .withHeader("Authorization", equalTo("Bearer test-token")));
        }

        @Test
        @DisplayName("pagination: page1=2 (limit=2), page2=1 → 3 jobs, offset 0 then 2, no offset=4")
        void pagination() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("0"))
                    .willReturn(okJson(rows(
                            row("1", "A", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1"),
                            row("2", "B", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/2")))));
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("2"))
                    .willReturn(okJson(rows(row("3", "C", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/3")))));

            FetchResult result = strategy.fetch(context(50, Map.of("limit", 2)));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            wireMockServer.verify(getRequestedFor(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("0")));
            wireMockServer.verify(getRequestedFor(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("2")));
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("4")));
        }

        @Test
        @DisplayName("max-results cap: maxResults=2, page1 has 3 rows → no page2 requested")
        void maxResultsCap() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("0"))
                    .willReturn(okJson(rows(
                            row("1", "A", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1"),
                            row("2", "B", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/2"),
                            row("3", "C", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/3")))));

            FetchResult result = strategy.fetch(context(2, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            // The loop stops once seen.size() >= maxResults; it does not truncate the fetched page.
            assertThat(result.jobs()).hasSize(3);
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("2")));
        }

        @Test
        @DisplayName("visa-confirmed-only=true (default) → request URL contains visa_confirmed=eq.Yes")
        void visaConfirmedOnlyDefault() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(row("1", "A", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1")))));

            strategy.fetch(context(50, Map.of()));

            boolean anyVisaParam = wireMockServer.getAllServeEvents().stream()
                    .anyMatch(e -> e.getRequest().getUrl() != null
                            && e.getRequest().getUrl().contains("visa_confirmed=eq.Yes"));
            assertThat(anyVisaParam).isTrue();
        }

        @Test
        @DisplayName("visa-confirmed-only=false → request URL has no visa_confirmed param")
        void visaConfirmedOnlyFalse() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(row("1", "A", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1")))));

            strategy.fetch(context(50, Map.of("visa-confirmed-only", false)));

            boolean anyVisaParam = wireMockServer.getAllServeEvents().stream()
                    .anyMatch(e -> e.getRequest().getUrl() != null
                            && e.getRequest().getUrl().contains("visa_confirmed="));
            assertThat(anyVisaParam).isFalse();
        }

        @Test
        @DisplayName("dedup by job_id: two rows sharing job_id returned once")
        void dedupByJobId() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(
                            row("dup", "First", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/dup"),
                            row("dup", "Second", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/dup"),
                            row("uniq", "Unique", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/uniq")))));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
        }

        @Test
        @DisplayName("externalId >255 chars → 64-char SHA-256 hex")
        void externalIdDerivation() throws Exception {
            String longId = "a".repeat(300);
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(
                            row("short", "Short", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/short"),
                            row(longId, "Long", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/long")))));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            RawAggregatorJob shortJob = result.jobs().stream()
                    .filter(j -> j.title().equals("Short")).findFirst().orElseThrow();
            RawAggregatorJob longJob = result.jobs().stream()
                    .filter(j -> j.title().equals("Long")).findFirst().orElseThrow();

            assertThat(shortJob.externalId()).isEqualTo("short");
            assertThat(longJob.externalId()).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(longJob.externalId()).isEqualTo(sha256Hex(longId));
        }

        @Test
        @DisplayName("null title / null url / null job_id rows skipped, valid row returned")
        void nullFieldsSkipped() {
            String noTitle = """
                    {"job_id":"nt","title":null,"company":"Co","location":"Berlin","description_text":"d","url":"https://www.arbeitnow.com/jobs/nt","posted":"2026-08-25T08:55:35+00:00"}
                    """;
            String noUrl = """
                    {"job_id":"nu","title":"No Url","company":"Co","location":"Berlin","description_text":"d","url":null,"posted":"2026-08-25T08:55:35+00:00"}
                    """;
            String noId = """
                    {"job_id":null,"title":"No Id","company":"Co","location":"Berlin","description_text":"d","url":"https://www.arbeitnow.com/jobs/nid","posted":"2026-08-25T08:55:35+00:00"}
                    """;
            String valid = row("v", "Valid", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/v");
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(noTitle, noUrl, noId, valid))));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Valid");
        }

        @Test
        @DisplayName("401 then refresh+retry succeeds → SUCCESS")
        void retryAfter401() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .inScenario("list")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(401))
                    .willSetStateTo("ok"));
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .inScenario("list")
                    .whenScenarioStateIs("ok")
                    .willReturn(okJson(rows(row("1", "Retry Role", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1")))));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/rest/v1/jobs")));
            // token fetched before the first GET and again after the 401 invalidate
            wireMockServer.verify(2, postRequestedFor(urlPathEqualTo("/auth/v1/token")));
        }

        @Test
        @DisplayName("401 then refresh fails → PROTECTED")
        void refreshFailureAfter401() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(aResponse().withStatus(401)));
            wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                    .withQueryParam("grant_type", equalTo("refresh_token"))
                    .inScenario("auth")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{\"access_token\":\"test-token\",\"expires_in\":3600}"))
                    .willSetStateTo("fail"));
            wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                    .withQueryParam("grant_type", equalTo("refresh_token"))
                    .inScenario("auth")
                    .whenScenarioStateIs("fail")
                    .willReturn(aResponse().withStatus(400)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
        }

        @Test
        @DisplayName("revoked refresh token → PROTECTED, no list request")
        void revokedRefreshToken() {
            wireMockServer.stubFor(post(urlPathEqualTo("/auth/v1/token"))
                    .withQueryParam("grant_type", equalTo("refresh_token"))
                    .willReturn(aResponse().withStatus(400)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/rest/v1/jobs")));
        }

        @Test
        @DisplayName("429 on list → RATE_LIMITED")
        void rateLimited() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(aResponse().withStatus(429).withBody("slow down")));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("malformed JSON body → ERROR with non-blank errorMessage")
        void malformedJson() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{not valid json")));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("empty array [] → EMPTY")
        void emptyArray() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson("[]")));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("late-page failure: page1 ok, page2 500 → SUCCESS with partial 2 jobs")
        void latePageFailureReturnsPartial() {
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("0"))
                    .willReturn(okJson(rows(
                            row("1", "Page1 A", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/1"),
                            row("2", "Page1 B", "Co", "Berlin", "d", "https://www.arbeitnow.com/jobs/2")))));
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .withQueryParam("offset", equalTo("2"))
                    .willReturn(aResponse().withStatus(500).withBody("boom")));

            FetchResult result = strategy.fetch(context(50, Map.of("limit", 2)));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Page1 A");
        }

        @Test
        @DisplayName("posted non-ISO → postedDate null, job still returned")
        void nonIsoPostedDate() {
            String badDate = """
                    {"job_id":"bd","title":"Bad Date","company":"Co","location":"Berlin","description_text":"d","url":"https://www.arbeitnow.com/jobs/bd","posted":"not-a-date"}
                    """;
            wireMockServer.stubFor(get(urlPathEqualTo("/rest/v1/jobs"))
                    .willReturn(okJson(rows(badDate))));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).postedDate()).isNull();
        }

        @Test
        @DisplayName("missing url config → ERROR, errorMessage contains url")
        void missingUrlConfig() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 10,
                    Map.of("apikey", "anon-key", "auth-url", baseUrl + "/auth/v1/token"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("url");
        }

        @Test
        @DisplayName("missing apikey config → ERROR")
        void missingApikeyConfig() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 10,
                    Map.of("url", baseUrl + "/rest/v1/jobs", "auth-url", baseUrl + "/auth/v1/token"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        }
    }
}
