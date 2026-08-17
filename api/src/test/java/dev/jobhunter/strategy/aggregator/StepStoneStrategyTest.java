package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WireMockTest
class StepStoneStrategyTest {

    private static final String DETAIL_PATH = "/stellenangebote--backend-engineer--12345-inline.html";
    private static final String SECOND_DETAIL_PATH = "/stellenangebote--java-developer--67890-inline.html";

    private JobPostingRepository repository;
    private StepStoneStrategy strategy;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        baseUrl = wireMock.getHttpBaseUrl();
        repository = mock(JobPostingRepository.class);
        when(repository.findExternalIdsBySource(JobSource.STEPSTONE)).thenReturn(List.of());
        strategy = new StepStoneStrategy(WebClient.builder().build(), repository);
    }

    @Test
    void metadata_isRegisteredAsSearchOnlyStrategy() {
        assertThat(strategy.name()).isEqualTo("stepstone");
        assertThat(strategy.supportedTypes()).isEmpty();
    }

    @Test
    void fetch_harvestsRelativeLinksAndMapsJsonLd() {
        stubSearch("backend-engineer", "berlin", """
                <html><body>
                  <a href="/stellenangebote--backend-engineer--12345-inline.html">valid</a>
                  <a href="/company/acme">noise</a>
                  <a href="https://example.com/stellenangebote--other--99-inline.html">other host</a>
                </body></html>
                """);
        stubFor(get(urlPathEqualTo(DETAIL_PATH)).willReturn(okHtml("""
                <html><body><h1>Ignored fallback title</h1>
                  <script type="application/ld+json">
                  {"@type":"JobPosting","title":"Backend Engineer","description":"Build APIs",
                   "datePosted":"2026-01-15","hiringOrganization":{"name":"Acme GmbH"},
                   "jobLocation":[{"address":{"addressLocality":"Berlin"}},
                                 {"address":{"addressLocality":"Munich"}}],
                   "baseSalary":{"currency":"EUR","unitText":"YEAR",
                                  "value":{"minValue":80000,"maxValue":95000}}}
                  </script>
                </body></html>
                """)));

        FetchResult result = strategy.fetch(context(List.of("backend engineer"), List.of("berlin"), Map.of()));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("12345");
            assertThat(job.title()).isEqualTo("Backend Engineer");
            assertThat(job.companyName()).isEqualTo("Acme GmbH");
            assertThat(job.location()).isEqualTo("Berlin, Munich");
            assertThat(job.description()).isEqualTo("Build APIs");
            assertThat(job.postedDate()).hasToString("2026-01-15");
            assertThat(job.salaryMin()).hasToString("80000");
            assertThat(job.salaryMax()).hasToString("95000");
            assertThat(job.salaryCurrency()).isEqualTo("EUR");
            assertThat(job.applyUrl()).isEqualTo(baseUrl + DETAIL_PATH);
            assertThat(job.rawJson()).contains("Backend Engineer");
        });
        verify(getRequestedFor(urlPathEqualTo("/jobs/backend-engineer/in-berlin"))
                .withQueryParam("q", equalTo("backend engineer")));
    }

    @Test
    void fetch_deduplicatesAcrossCitiesAndSkipsKnownIds() {
        String search = "<a href=\"" + DETAIL_PATH + "\">one</a>"
                + "<a href=\"" + SECOND_DETAIL_PATH + "\">two</a>";
        stubSearch("java-developer", "berlin", search);
        stubSearch("java-developer", "muenchen", search);
        stubFor(get(urlPathEqualTo(DETAIL_PATH)).willReturn(okHtml(detailJson("Backend Engineer", "12345"))));
        stubFor(get(urlPathEqualTo(SECOND_DETAIL_PATH)).willReturn(okHtml(detailJson("Java Developer", "67890"))));
        when(repository.findExternalIdsBySource(JobSource.STEPSTONE)).thenReturn(List.of("12345"));

        FetchResult result = strategy.fetch(context(List.of("java developer"), List.of("berlin", "muenchen"), Map.of()));

        assertThat(result.jobs()).singleElement().extracting(RawAggregatorJob::externalId).isEqualTo("67890");
        verify(1, getRequestedFor(urlPathEqualTo(SECOND_DETAIL_PATH)));
        verify(0, getRequestedFor(urlPathEqualTo(DETAIL_PATH)));
    }

    @Test
    void fetch_honorsDetailCap() {
        stubSearch("backend-engineer", null, "<a href=\"" + DETAIL_PATH + "\">one</a>"
                + "<a href=\"" + SECOND_DETAIL_PATH + "\">two</a>");
        stubFor(get(urlPathEqualTo(DETAIL_PATH)).willReturn(okHtml(detailJson("Backend Engineer", "12345"))));
        stubFor(get(urlPathEqualTo(SECOND_DETAIL_PATH)).willReturn(okHtml(detailJson("Java Developer", "67890"))));

        FetchResult result = strategy.fetch(context(List.of("backend engineer"), List.of(), Map.of("maxScrapePerRun", "1")));

        assertThat(result.jobs()).hasSize(1);
        verify(0, getRequestedFor(urlPathEqualTo(SECOND_DETAIL_PATH)));
    }

    @Test
    void fetch_detailErrorsPreservePartialResults() {
        stubSearch("backend-engineer", null, "<a href=\"" + DETAIL_PATH + "\">one</a>"
                + "<a href=\"" + SECOND_DETAIL_PATH + "\">two</a>");
        stubFor(get(urlPathEqualTo(DETAIL_PATH)).willReturn(okHtml(detailJson("Backend Engineer", "12345"))));
        stubFor(get(urlPathEqualTo(SECOND_DETAIL_PATH)).willReturn(aResponse().withStatus(429)));

        FetchResult result = strategy.fetch(context(List.of("backend engineer"), List.of(), Map.of()));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).singleElement().extracting(RawAggregatorJob::externalId).isEqualTo("12345");
    }

    @Test
    void fetch_skipsMissingDetailAndFallsBackToH1() {
        String fallbackPath = "/stellenangebote--fallback--no-id-inline.html";
        String untitledPath = "/stellenangebote--untitled--no-id-inline.html";
        stubSearch("backend-engineer", null, "<a href=\"" + fallbackPath + "\">fallback</a>"
                + "<a href=\"" + untitledPath + "\">untitled</a>"
                + "<a href=\"" + SECOND_DETAIL_PATH + "\">missing</a>");
        stubFor(get(urlPathEqualTo(fallbackPath)).willReturn(okHtml("<html><body><h1>Fallback role</h1></body></html>")));
        stubFor(get(urlPathEqualTo(untitledPath)).willReturn(okHtml("<html><body><p>No title</p></body></html>")));
        stubFor(get(urlPathEqualTo(SECOND_DETAIL_PATH)).willReturn(aResponse().withStatus(404)));

        FetchResult result = strategy.fetch(context(List.of("backend engineer"), List.of(), Map.of()));

        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.title()).isEqualTo("Fallback role");
            assertThat(job.externalId()).isNotBlank();
            assertThat(job.applyUrl()).isEqualTo(baseUrl + fallbackPath);
        });
    }

    @Test
    void fetch_searchStatusesAndEmptyQueriesAreHandled() {
        stubSearchStatus("backend-engineer", null, 429);
        FetchResult limited = strategy.fetch(context(List.of("backend engineer"), List.of(), Map.of()));
        assertThat(limited.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);

        stubSearchStatus("java-developer", null, 403);
        FetchResult protectedResult = strategy.fetch(context(List.of("java developer"), List.of(), Map.of()));
        assertThat(protectedResult.status()).isIn(ExtractionStatus.ERROR, ExtractionStatus.PROTECTED);

        FetchResult empty = strategy.fetch(context(List.of(), List.of(), Map.of()));
        assertThat(empty.status()).isEqualTo(ExtractionStatus.ERROR);
    }

    private FetchContext context(List<String> keywords, List<String> cities, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("url", baseUrl);
        config.put("delayBetweenMs", "0");
        config.put("maxScrapePerRun", "40");
        if (!cities.isEmpty()) {
            config.put("cities", String.join(",", cities));
        }
        config.putAll(extra);
        return FetchContext.forSearch(keywords, cities, 100, 1, config);
    }

    private void stubSearch(String keywordSlug, String city, String body) {
        String path = "/jobs/" + keywordSlug + (city == null ? "" : "/in-" + city);
        stubFor(get(urlPathEqualTo(path))
                .withQueryParam("q", matching(".+"))
                .willReturn(okHtml(body)));
    }

    private void stubSearchStatus(String keywordSlug, String city, int status) {
        String path = "/jobs/" + keywordSlug + (city == null ? "" : "/in-" + city);
        stubFor(get(urlPathEqualTo(path)).withQueryParam("q", matching(".+"))
                .willReturn(aResponse().withStatus(status)));
    }

    private static ResponseDefinitionBuilder okHtml(String body) {
        return ok(body).withHeader("Content-Type", "text/html");
    }

    private static String detailJson(String title, String id) {
        return "<html><body><script type=\"application/ld+json\">"
                + "{\"@type\":\"JobPosting\",\"title\":\"" + title + "\","
                + "\"hiringOrganization\":{\"name\":\"Acme\"},\"datePosted\":\"2026-01-01\","
                + "\"jobLocation\":{\"address\":{\"addressLocality\":\"Berlin\"}},"
                + "\"description\":\"Description\",\"baseSalary\":{\"currency\":\"EUR\","
                + "\"unitText\":\"YEAR\",\"value\":100000}}"
                + "</script></body></html>";
    }
}
