package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalMoveStrategy}: WireMock serves the Inertia HTML
 * shell embedding {@code <script data-page="app">} JSON; the session manager is
 * a Mockito mock so cookie sourcing/mark-dead can be asserted in isolation.
 */
class GlobalMoveStrategyTest {

    private static final String SESSION_COOKIE = "test-session";
    private static final String COOKIE_HEADER = "the-global-move-session=" + SESSION_COOKIE;
    private static final String COUNTRIES_DE_NL = "[\"Germany\",\"Netherlands\"]";
    private static final String GREENHOUSE = "https://job-boards.greenhouse.io/acme/jobs/";

    private WireMockServer wireMockServer;
    private GlobalMoveSessionManager sessionManager;
    private GlobalMoveStrategy strategy;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        baseUrl = "http://localhost:" + wireMockServer.port();

        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        sessionManager = mock(GlobalMoveSessionManager.class);
        when(sessionManager.isConfigured()).thenReturn(true);
        when(sessionManager.isValid()).thenReturn(true);
        when(sessionManager.getSessionCookie()).thenReturn(SESSION_COOKIE);
        strategy = new GlobalMoveStrategy(webClient, sessionManager);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private FetchContext context(int maxResults, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("url", baseUrl + "/jobs");
        config.putAll(extra);
        return FetchContext.forSearch(List.of(), List.of(), maxResults, 10, config);
    }

    private static String html(String inertiaJson) {
        return "<!DOCTYPE html><html><head></head><body><div id=\"app\"></div>"
                + "<script data-page=\"app\" type=\"application/json\">"
                + inertiaJson
                + "</script></body></html>";
    }

    private static String paginator(String dataJson, String nextPageUrl) {
        String next = nextPageUrl == null ? "null" : "\"" + nextPageUrl + "\"";
        return "{\"component\":\"jobs/index\",\"props\":{\"jobs\":{"
                + "\"current_page\":1,\"data\":[" + dataJson + "]"
                + ",\"next_page_url\":" + next
                + ",\"per_page\":20,\"total\":1,\"last_page\":1,"
                + "\"path\":\"https://globalmove.relocate.me/jobs\"}}}";
    }

    private static String job(int id, String name, String applyUrl, String postedDate, String countriesJson) {
        return "{\"id\":" + id
                + ",\"name\":" + quote(name)
                + ",\"apply_url\":" + quote(applyUrl)
                + ",\"categories\":[\"Back End\"]"
                + ",\"posted_date\":" + quote(postedDate)
                + ",\"countries\":" + countriesJson
                + ",\"company\":\"Acme\""
                + ",\"company_size\":\"51-200\""
                + ",\"industry\":\"Software\"}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String nextPageUrl(int page) {
        return "https://globalmove.relocate.me/jobs?page=" + page;
    }

    private void stubPageOne(String htmlBody) {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs"))
                .withQueryParam("page", absent())
                .willReturn(okHtml(htmlBody)));
    }

    private void stubPage(int page, String htmlBody) {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(okHtml(htmlBody)));
    }

    private void stubPageStatus(int page, int status) {
        wireMockServer.stubFor(get(urlPathEqualTo("/jobs"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(aResponse().withStatus(status).withBody("boom")));
    }

    private static ResponseDefinitionBuilder okHtml(String body) {
        return aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody(body);
    }

    // ── metadata ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("name() returns globalmove")
    void nameReturnsGlobalMove() {
        assertThat(strategy.name()).isEqualTo("globalmove");
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
        @DisplayName("happy path single page: 3 items → SUCCESS with correct field mapping")
        void happyPathSinglePage() {
            String data = job(4765, "Senior Backend Engineer", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL)
                    + "," + job(4766, "Backend Developer", GREENHOUSE + "2", "3d ago", "[\"Germany\"]")
                    + "," + job(4767, "Data Engineer", GREENHOUSE + "3", "garbage", "[]");
            stubPageOne(html(paginator(data, null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            RawAggregatorJob job0 = result.jobs().get(0);
            assertThat(job0.externalId()).isEqualTo("gm-4765");
            assertThat(job0.title()).isEqualTo("Senior Backend Engineer");
            assertThat(job0.companyName()).isEqualTo("Acme");
            assertThat(job0.location()).isEqualTo("Germany, Netherlands");
            assertThat(job0.applyUrl()).isEqualTo(GREENHOUSE + "1");
            assertThat(job0.description()).isNull();
        }

        @Test
        @DisplayName("session cookie sent as the-global-move-session and no XSRF token")
        void sessionCookieSent() {
            stubPageOne(html(paginator(
                    job(1, "Role", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL), null)));

            strategy.fetch(context(50, Map.of()));

            wireMockServer.verify(getRequestedFor(urlPathEqualTo("/jobs"))
                    .withHeader("Cookie", equalTo(COOKIE_HEADER)));

            boolean anyXsrf = wireMockServer.getAllServeEvents().stream().anyMatch(e -> {
                String cookie = e.getRequest().getHeader("Cookie");
                return (cookie != null && cookie.toUpperCase(Locale.ROOT).contains("XSRF"))
                        || e.getRequest().getHeader("X-XSRF-TOKEN") != null;
            });
            assertThat(anyXsrf).isFalse();
        }

        @Test
        @DisplayName("pagination via next_page_url: page1 and page2 fetched, stops on null")
        void paginationViaNextPageUrl() {
            stubPageOne(html(paginator(
                    job(1, "Page1 A", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL)
                            + "," + job(2, "Page1 B", GREENHOUSE + "2", "NEW", COUNTRIES_DE_NL),
                    nextPageUrl(2))));
            stubPage(2, html(paginator(
                    job(3, "Page2 A", GREENHOUSE + "3", "NEW", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/jobs")));
            wireMockServer.verify(getRequestedFor(urlPathEqualTo("/jobs"))
                    .withQueryParam("page", equalTo("2")));
        }

        @Test
        @DisplayName("max-results cap: maxResults=2 with 3 items → returns 2, no page2 requested")
        void maxResultsCap() {
            String data = job(1, "A", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL)
                    + "," + job(2, "B", GREENHOUSE + "2", "NEW", COUNTRIES_DE_NL)
                    + "," + job(3, "C", GREENHOUSE + "3", "NEW", COUNTRIES_DE_NL);
            stubPageOne(html(paginator(data, nextPageUrl(2))));

            FetchResult result = strategy.fetch(context(2, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/jobs"))
                    .withQueryParam("page", equalTo("2")));
        }

        @Test
        @DisplayName("posted_date NEW → postedDate today")
        void postedDateNew() {
            stubPageOne(html(paginator(
                    job(1, "Fresh", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("posted_date '3d ago' → postedDate today minus 3")
        void postedDateRelative() {
            stubPageOne(html(paginator(
                    job(1, "Older", GREENHOUSE + "1", "3d ago", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.now().minusDays(3));
        }

        @Test
        @DisplayName("posted_date unparseable → postedDate null, job still returned")
        void postedDateUnparseable() {
            stubPageOne(html(paginator(
                    job(1, "Mystery", GREENHOUSE + "1", "garbage", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).postedDate()).isNull();
        }

        @Test
        @DisplayName("externalId prefix: id 1234 → gm-1234")
        void externalIdPrefixed() {
            stubPageOne(html(paginator(
                    job(1234, "Role", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).externalId()).isEqualTo("gm-1234");
        }

        @Test
        @DisplayName("missing id / name / apply_url rows skipped, valid row returned")
        void missingFieldsSkipped() {
            String noId = "{\"name\":\"No Id\",\"apply_url\":\"" + GREENHOUSE + "nid\","
                    + "\"countries\":[\"Germany\"],\"company\":\"Acme\"}";
            String noName = "{\"id\":2,\"apply_url\":\"" + GREENHOUSE + "nn\","
                    + "\"countries\":[\"Germany\"],\"company\":\"Acme\"}";
            String noUrl = "{\"id\":3,\"name\":\"No Url\","
                    + "\"countries\":[\"Germany\"],\"company\":\"Acme\"}";
            String valid = job(4, "Valid", GREENHOUSE + "v", "NEW", COUNTRIES_DE_NL);
            stubPageOne(html(paginator(noId + "," + noName + "," + noUrl + "," + valid, null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Valid");
            assertThat(result.jobs().get(0).externalId()).isEqualTo("gm-4");
        }

        @Test
        @DisplayName("countries[] joined: [Germany, Netherlands] → 'Germany, Netherlands'")
        void countriesJoined() {
            stubPageOne(html(paginator(
                    job(1, "Multi", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs().get(0).location()).isEqualTo("Germany, Netherlands");
        }

        @Test
        @DisplayName("login payload (dead session) → PROTECTED and markDead() called")
        void loginPageProtected() {
            stubPageOne(html("{\"component\":\"auth/login\",\"props\":{\"errors\":{}}}"));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
            assertThat(result.jobs()).isEmpty();
            verify(sessionManager).markDead();
        }

        @Test
        @DisplayName("HTTP 401 → PROTECTED and markDead() called")
        void http401Protected() {
            wireMockServer.stubFor(get(urlPathEqualTo("/jobs"))
                    .withQueryParam("page", absent())
                    .willReturn(aResponse().withStatus(401)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
            verify(sessionManager).markDead();
        }

        @Test
        @DisplayName("HTTP 403 → PROTECTED")
        void http403Protected() {
            wireMockServer.stubFor(get(urlPathEqualTo("/jobs"))
                    .withQueryParam("page", absent())
                    .willReturn(aResponse().withStatus(403)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
            verify(sessionManager).markDead();
        }

        @Test
        @DisplayName("unconfigured session → PROTECTED, never empty, cookie never read")
        void unconfiguredSessionProtected() {
            when(sessionManager.isConfigured()).thenReturn(false);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
            assertThat(result.status()).isNotEqualTo(ExtractionStatus.EMPTY);
            assertThat(result.jobs()).isEmpty();
            verify(sessionManager, never()).getSessionCookie();
        }

        @Test
        @DisplayName("structural change: jobs/index component without props.jobs → ERROR, not empty")
        void structuralChangeError() {
            stubPageOne(html("{\"component\":\"jobs/index\",\"props\":{\"user\":null}}"));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("HTTP 429 → RATE_LIMITED")
        void rateLimited() {
            wireMockServer.stubFor(get(urlPathEqualTo("/jobs"))
                    .withQueryParam("page", absent())
                    .willReturn(aResponse().withStatus(429).withBody("slow down")));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("malformed embedded JSON → ERROR")
        void malformedEmbeddedJson() {
            stubPageOne(html("{ this is not valid json"));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("empty data[] → EMPTY")
        void emptyData() {
            stubPageOne(html(paginator("", null)));

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
            assertThat(result.jobs()).isEmpty();
        }

        @Test
        @DisplayName("late-page failure: page1 ok, page2 500 → partial page1 jobs returned")
        void latePageFailurePartial() {
            stubPageOne(html(paginator(
                    job(1, "Page1 A", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL)
                            + "," + job(2, "Page1 B", GREENHOUSE + "2", "NEW", COUNTRIES_DE_NL),
                    nextPageUrl(2))));
            stubPageStatus(2, 500);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Page1 A");
            assertThat(result.jobs().get(1).title()).isEqualTo("Page1 B");
        }

        @Test
        @DisplayName("delay-between-pages-ms honored: 2 pages, 200ms → elapsed >= 200ms")
        void delayBetweenPagesHonored() {
            stubPageOne(html(paginator(
                    job(1, "Page1 A", GREENHOUSE + "1", "NEW", COUNTRIES_DE_NL),
                    nextPageUrl(2))));
            stubPage(2, html(paginator(
                    job(2, "Page2 A", GREENHOUSE + "2", "NEW", COUNTRIES_DE_NL), null)));

            FetchResult result = strategy.fetch(context(50, Map.of("delay-between-pages-ms", 200)));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        }

        @Test
        @DisplayName("missing url config → ERROR, errorMessage contains url")
        void missingUrlConfig() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 10, Map.of());

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("url");
        }
    }
}
