package dev.jobhunter.strategy.ats;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ScreenLoopStrategyTest {

    private static final String COMPANY_INFO =
            "\"companyInfo\":{\"organizationId\":364,\"name\":\"Otera\",\"subdomain\":\"otera\"}";

    private WireMockServer wireMockServer;
    private ScreenLoopStrategy strategy;
    private String boardUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        boardUrl = "http://localhost:" + wireMockServer.port();
        WebClient webClient = WebClient.builder().baseUrl(boardUrl).build();
        strategy = new ScreenLoopStrategy(webClient);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    // ── metadata ──────────────────────────────────────────────────────────────

    @Test
    void name_returnsScreenloop() {
        assertThat(strategy.name()).isEqualTo("screenloop");
    }

    @Test
    void supportedTypes_containsScreenloop() {
        assertThat(strategy.supportedTypes()).containsExactly(AtsType.SCREENLOOP);
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private FetchContext context() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url(boardUrl)
                .atsType(AtsType.SCREENLOOP)
                .build();
        return FetchContext.forEndpoint(endpoint);
    }

    /** Entity-escapes JSON so it can live inside an HTML attribute (Jsoup decodes it back). */
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String pageHtml(String propsJson) {
        return "<html><body><div id=\"board\" data-react-class=\"BoardPage\" data-react-props=\""
                + escapeHtml(propsJson) + "\"></div></body></html>";
    }

    private static String jobPostJson(String id, String name, String location, String countryCode) {
        return "{"
                + "\"id\":" + id + ","
                + "\"name\":\"" + name + "\","
                + "\"location\":{\"id\":1,\"name\":\"" + location + "\",\"country\":\"Germany\","
                + "\"countryCode\":\"" + countryCode + "\"},"
                + "\"job\":{\"id\":1,\"name\":\"" + name + "\","
                + "\"department\":{\"id\":1,\"name\":\"Tech\"}}"
                + "}";
    }

    private static String listPropsJson(String... jobPosts) {
        return "{" + COMPANY_INFO + ",\"jobPosts\":[" + String.join(",", jobPosts) + "]}";
    }

    private static String detailPropsJson(String id, String name, String location,
                                          String locationCountry, String descriptionHtml) {
        return "{"
                + "\"previewJobPost\":{"
                + "\"name\":\"" + name + "\","
                + "\"department\":\"Tech\","
                + "\"location\":\"" + location + "\","
                + "\"locationCountry\":\"" + locationCountry + "\","
                + "\"locationType\":\"Onsite\","
                + "\"employmentType\":\"Full time\","
                + "\"jobPostId\":" + id + ","
                + "\"status\":\"published\","
                + "\"salary\":null"
                + "},"
                + "\"jobPostHtmlString\":\"" + descriptionHtml + "\","
                + COMPANY_INFO
                + "}";
    }

    private void stubListPage(String propsJson) {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody(pageHtml(propsJson))));
    }

    private void stubDetailPage(String id, String propsJson) {
        wireMockServer.stubFor(get(urlPathEqualTo("/job_posts/" + id)).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody(pageHtml(propsJson))));
    }

    // ── fetch() ───────────────────────────────────────────────────────────────

    @Test
    void fetch_happyPath_mapsListAndDetailJobs() {
        stubListPage(listPropsJson(
                jobPostJson("8536", "Platform Engineer (f/m/d)- Remote Europe", "Remote Europe", "DE"),
                jobPostJson("8537", "Backend Engineer", "Munich", "DE")));
        stubDetailPage("8536", detailPropsJson("8536", "Platform Engineer (f/m/d)",
                "Remote Europe", "Germany", "<p>Hello <b>World</b></p>"));
        stubDetailPage("8537", detailPropsJson("8537", "Backend Engineer",
                "Munich", "Germany", "<p>Join our <b>platform</b> team</p>"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);

        // Detail name preferred over list name (list title has trailing location suffix).
        RawAggregatorJob first = result.jobs().get(0);
        assertThat(first.externalId()).isEqualTo("8536");
        assertThat(first.title()).isEqualTo("Platform Engineer (f/m/d)");
        assertThat(first.companyName()).isEqualTo("Otera");
        assertThat(first.location()).isEqualTo("Remote Europe");
        assertThat(first.description()).isEqualTo("Hello World");
        assertThat(first.applyUrl()).isEqualTo(boardUrl + "/job_posts/8536");
        assertThat(first.postedDate()).isNull();
        assertThat(first.salaryMin()).isNull();
        assertThat(first.salaryMax()).isNull();
        assertThat(first.salaryCurrency()).isNull();
        assertThat(first.rawJson()).contains("\"previewJobPost\"").contains("\"jobPostHtmlString\"");

        RawAggregatorJob second = result.jobs().get(1);
        assertThat(second.externalId()).isEqualTo("8537");
        assertThat(second.title()).isEqualTo("Backend Engineer");
        assertThat(second.companyName()).isEqualTo("Otera");
        assertThat(second.location()).isEqualTo("Munich");
        assertThat(second.description()).isEqualTo("Join our platform team");
        assertThat(second.applyUrl()).isEqualTo(boardUrl + "/job_posts/8537");

        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/job_posts/8536")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/job_posts/8537")));
    }

    @Test
    void fetch_detailFailure_returnsPartialSuccess() {
        stubListPage(listPropsJson(
                jobPostJson("8536", "Platform Engineer", "Remote Europe", "DE"),
                jobPostJson("8537", "Backend Engineer", "Munich", "DE")));
        stubDetailPage("8536", detailPropsJson("8536", "Platform Engineer",
                "Remote Europe", "Germany", "<p>Hello <b>World</b></p>"));
        wireMockServer.stubFor(get(urlPathEqualTo("/job_posts/8537")).willReturn(aResponse()
                .withStatus(500)
                .withBody("internal error")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("8536");
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/job_posts/8537")));
    }

    @Test
    void fetch_emptyJobPosts_returnsEmpty() {
        stubListPage(listPropsJson());

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_missingDataReactProps_returnsError() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<html><body><div>no react props here</div></body></html>")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_forbiddenOnListPage_returnsProtected() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(403)
                .withBody("forbidden")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_serverErrorOnListPage_returnsError() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(500)
                .withBody("boom")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_descriptionHtml_isStrippedToText() {
        stubListPage(listPropsJson(
                jobPostJson("42", "Engineer", "Berlin", "DE")));
        stubDetailPage("42", detailPropsJson("42", "Engineer",
                "Berlin", "Germany", "<p>Hello <b>World</b></p>"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).description()).isEqualTo("Hello World");
    }
}
