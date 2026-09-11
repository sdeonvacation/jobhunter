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

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class PhenomStrategyTest {

    private static final String KARLSRUHE_OBJECT =
            "{\"@type\":\"Place\",\"geo\":{\"@type\":\"GeoCoordinates\",\"latitude\":49.0,\"longitude\":8.4},"
                    + "\"address\":{\"@type\":\"PostalAddress\",\"addressCountry\":\"Deutschland\","
                    + "\"addressLocality\":\"Karlsruhe\",\"addressRegion\":\"\"}}";

    private static final String STUTTGART_ARRAY =
            "[{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\","
                    + "\"addressCountry\":\"Deutschland\",\"addressLocality\":\"Stuttgart\",\"addressRegion\":\"\"}}]";

    private WireMockServer wireMockServer;
    private PhenomStrategy strategy;
    private String boardUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        boardUrl = "http://localhost:" + wireMockServer.port();
        WebClient webClient = WebClient.builder().baseUrl(boardUrl).build();
        strategy = new PhenomStrategy(webClient);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    // ── metadata ──────────────────────────────────────────────────────────────

    @Test
    void name_returnsPhenom() {
        assertThat(strategy.name()).isEqualTo("phenom");
    }

    @Test
    void supportedTypes_containsPhenom() {
        assertThat(strategy.supportedTypes()).containsExactly(AtsType.PHENOM);
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private FetchContext context() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url(boardUrl + "/de/de")
                .atsType(AtsType.PHENOM)
                .build();
        return FetchContext.forEndpoint(endpoint);
    }

    private String jobUrl(String reqId, String slug) {
        return boardUrl + "/de/de/job/EBQEBQGLOBAL" + reqId + "EXTERNALDEDE/" + slug;
    }

    private static String sitemap(String... locs) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (String loc : locs) {
            sb.append("<url><loc>").append(loc).append("</loc></url>");
        }
        sb.append("</urlset>");
        return sb.toString();
    }

    /** Mirrors EnBW: the description is HTML markup that has been entity-escaped inside the JSON. */
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Escapes control characters so the fixture stays valid JSON. */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String jobPage(String title, String datePosted, String idValue,
                                  String jobLocationJson, String rawDescriptionHtml) {
        String posting = "{"
                + "\"@context\":\"https://schema.org\","
                + "\"@type\":\"JobPosting\","
                + "\"title\":\"" + title + "\","
                + "\"datePosted\":\"" + datePosted + "\","
                + "\"identifier\":{\"@type\":\"PropertyValue\",\"name\":\"\",\"value\":\"" + idValue + "\"},"
                + "\"jobLocation\":" + jobLocationJson + ","
                + "\"description\":\"" + escapeJson(escapeHtml(rawDescriptionHtml)) + "\","
                + "\"employmentType\":\"FULL_TIME\","
                + "\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"EnBW\"}"
                + "}";
        String other = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"EnBW\"}";
        return "<html><head>"
                + "<script type=\"application/ld+json\">" + other + "</script>"
                + "<script type=\"application/ld+json\">" + posting + "</script>"
                + "</head><body>job detail</body></html>";
    }

    private void stubSitemap(String xml) {
        wireMockServer.stubFor(get(urlPathEqualTo("/de/de/sitemap.xml")).willReturn(aResponse()
                .withHeader("Content-Type", "application/xml")
                .withBody(xml)));
    }

    private void stubJobPage(String url, String html) {
        wireMockServer.stubFor(get(urlEqualTo(url.replace(boardUrl, ""))).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody(html)));
    }

    // ── fetch() ───────────────────────────────────────────────────────────────

    @Test
    void fetch_happyPath_mapsSitemapAndDetailJobs() {
        String url1 = jobUrl("25209", "Core-Trading-Platform-Engineer");
        String url2 = jobUrl("25328", "Praktikant-Softwareentwicklung");
        stubSitemap(sitemap(
                boardUrl + "/de/de",
                url1,
                url2,
                boardUrl + "/de/de/blog/karriere"));
        stubJobPage(url1, jobPage("Core Trading  Platform Engineer (m/f/d) - Digital Trading",
                "2026-08-13", "25209", KARLSRUHE_OBJECT,
                "<h3>Deine Aufgaben</h3>\n<p>Baue <b>Plattformen</b>.</p>"));
        stubJobPage(url2, jobPage("Praktikant Softwareentwicklung (m/w/d)",
                "2026-09-10T00:00:00.000+0000", "25328", STUTTGART_ARRAY,
                "<p>Werde Teil unseres <b>Teams</b>.</p>"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);

        RawAggregatorJob first = result.jobs().get(0);
        assertThat(first.externalId()).isEqualTo("25209");
        assertThat(first.title()).isEqualTo("Core Trading Platform Engineer (m/f/d) - Digital Trading");
        assertThat(first.companyName()).isEqualTo("EnBW");
        assertThat(first.location()).isEqualTo("Karlsruhe, Deutschland");
        assertThat(first.description()).isEqualTo("Deine Aufgaben Baue Plattformen.");
        assertThat(first.applyUrl()).isEqualTo(url1);
        assertThat(first.postedDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(first.salaryMin()).isNull();
        assertThat(first.salaryMax()).isNull();
        assertThat(first.salaryCurrency()).isNull();
        assertThat(first.rawJson()).contains("\"JobPosting\"");

        RawAggregatorJob second = result.jobs().get(1);
        assertThat(second.externalId()).isEqualTo("25328");
        assertThat(second.title()).isEqualTo("Praktikant Softwareentwicklung (m/w/d)");
        assertThat(second.location()).isEqualTo("Stuttgart, Deutschland");
        assertThat(second.applyUrl()).isEqualTo(url2);
        assertThat(second.postedDate()).isEqualTo(LocalDate.of(2026, 9, 10));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/de/de/sitemap.xml")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/de/de/job/EBQEBQGLOBAL25209EXTERNALDEDE/Core-Trading-Platform-Engineer")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/de/de/job/EBQEBQGLOBAL25328EXTERNALDEDE/Praktikant-Softwareentwicklung")));
    }

    @Test
    void fetch_timestampDatePosted_isParsed() {
        String url = jobUrl("25328", "Praktikant");
        stubSitemap(sitemap(url));
        stubJobPage(url, jobPage("Praktikant (m/w/d)", "2026-09-10T00:00:00.000+0000",
                "25328", KARLSRUHE_OBJECT, "<p>Text</p>"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void fetch_htmlEscapedDescription_isUnescapedThenStripped() {
        String url = jobUrl("25209", "Engineer");
        stubSitemap(sitemap(url));
        stubJobPage(url, jobPage("Engineer (m/f/d)", "2026-08-13", "25209", KARLSRUHE_OBJECT,
                "<h3>Hello</h3>\n<p>Join &amp; grow <b>now</b></p>"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).description()).isEqualTo("Hello Join & grow now");
    }

    @Test
    void fetch_jobLocationArray_mapsFirstPlace() {
        String url = jobUrl("25209", "Engineer");
        stubSitemap(sitemap(url));
        stubJobPage(url, jobPage("Engineer (m/f/d)", "2026-08-13", "25209", STUTTGART_ARRAY,
                "<p>Text</p>"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).location()).isEqualTo("Stuttgart, Deutschland");
    }

    @Test
    void fetch_detailServerError_returnsPartialSuccess() {
        String okUrl = jobUrl("25209", "Engineer");
        String brokenUrl = jobUrl("25328", "Broken");
        stubSitemap(sitemap(okUrl, brokenUrl));
        stubJobPage(okUrl, jobPage("Engineer (m/f/d)", "2026-08-13", "25209", KARLSRUHE_OBJECT,
                "<p>Text</p>"));
        wireMockServer.stubFor(get(urlEqualTo("/de/de/job/EBQEBQGLOBAL25328EXTERNALDEDE/Broken"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("25209");
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/de/de/job/EBQEBQGLOBAL25328EXTERNALDEDE/Broken")));
    }

    @Test
    void fetch_detailWithoutJobPosting_isSkipped() {
        String okUrl = jobUrl("25209", "Engineer");
        String noPostingUrl = jobUrl("25328", "NoPosting");
        stubSitemap(sitemap(okUrl, noPostingUrl));
        stubJobPage(okUrl, jobPage("Engineer (m/f/d)", "2026-08-13", "25209", KARLSRUHE_OBJECT,
                "<p>Text</p>"));
        stubJobPage(noPostingUrl, "<html><head><script type=\"application/ld+json\">"
                + "{\"@type\":\"Organization\",\"name\":\"EnBW\"}</script></head><body>no job</body></html>");

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("25209");
    }

    @Test
    void fetch_sitemapWithoutJobLocs_returnsEmpty() {
        stubSitemap(sitemap(boardUrl + "/de/de", boardUrl + "/de/de/blog/karriere"));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_sitemapForbidden_returnsProtected() {
        wireMockServer.stubFor(get(urlPathEqualTo("/de/de/sitemap.xml")).willReturn(aResponse()
                .withStatus(403)
                .withBody("forbidden")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_sitemapServerError_returnsError() {
        wireMockServer.stubFor(get(urlPathEqualTo("/de/de/sitemap.xml")).willReturn(aResponse()
                .withStatus(500)
                .withBody("boom")));

        FetchResult result = strategy.fetch(context());

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.jobs()).isEmpty();
    }
}
