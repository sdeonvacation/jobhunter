package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class JobleadsStrategyTest {

    private static final String TWO_CARDS_HTML = """
            <html><body>
              <div data-testid="search-job-card" data-job-id="abc123def456">
                <a data-testid="search-job-card-link" href="/de/job/senior-ai-engineer--muenchen--abc123def456">Senior AI Engineer</a>
                <p data-testid="search-job-card-company">
                  <span data-testid="search-job-card-company-name">Acme GmbH</span>
                  <span> • München</span>
                </p>
                <span data-testid="job-card-chip-salary">EUR 120.000 - 170.000</span>
                <span data-testid="job-card-date">14 Tage+</span>
              </div>
              <div data-testid="search-job-card" data-job-id="789abc000111">
                <a data-testid="search-job-card-link" href="/de/job/backend-engineer--berlin--789abc000111">Backend Engineer</a>
                <span data-testid="search-job-card-company-name">Beta SE</span>
                <span data-testid="job-card-chip-location">Berlin</span>
                <span data-testid="job-card-date">3 Tage</span>
              </div>
            </body></html>
            """;

    private JobleadsStrategy strategy;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        baseUrl = wireMock.getHttpBaseUrl();
        strategy = new JobleadsStrategy(WebClient.builder().build(), baseUrl + "/v1");
    }

    @Test
    void metadata_isRegisteredAsSearchOnlyStrategy() {
        assertThat(strategy.name()).isEqualTo("jobleads");
        assertThat(strategy.supportedTypes()).isEmpty();
    }

    @Test
    void fetch_singleKeywordParsesCards() {
        stubSearch("Applied%20AI%20Engineer", TWO_CARDS_HTML);

        FetchResult result = strategy.fetch(context(List.of("Applied AI Engineer")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0)).satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("abc123def456");
            assertThat(job.title()).isEqualTo("Senior AI Engineer");
            assertThat(job.companyName()).isEqualTo("Acme GmbH");
            assertThat(job.location()).isEqualTo("München");
            assertThat(job.applyUrl())
                    .isEqualTo("https://www.jobleads.com/de/job/senior-ai-engineer--muenchen--abc123def456");
            assertThat(job.salaryMin()).hasToString("120000");
            assertThat(job.salaryMax()).hasToString("170000");
            assertThat(job.salaryCurrency()).isEqualTo("EUR");
            assertThat(job.postedDate()).isEqualTo(LocalDate.now().minusDays(14));
            assertThat(job.description()).isNull();
            assertThat(job.rawJson()).isNull();
        });
        assertThat(result.jobs().get(1)).satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("789abc000111");
            assertThat(job.salaryMin()).isNull();
            assertThat(job.salaryMax()).isNull();
            assertThat(job.salaryCurrency()).isNull();
            assertThat(job.postedDate()).isEqualTo(LocalDate.now().minusDays(3));
        });
        verify(1, getRequestedFor(urlPathEqualTo("/de/jobs/q/Applied%20AI%20Engineer")));
    }

    @Test
    void fetch_multiKeywordCombinesAndDedupes() {
        stubSearch("Applied%20AI%20Engineer", TWO_CARDS_HTML);
        stubSearch("software%20engineer", """
                <html><body>
                  <div data-testid="search-job-card" data-job-id="789abc000111">
                    <a data-testid="search-job-card-link" href="/de/job/backend-engineer--berlin--789abc000111">Backend Engineer</a>
                    <span data-testid="search-job-card-company-name">Beta SE</span>
                    <span data-testid="job-card-chip-location">Berlin</span>
                    <span data-testid="job-card-date">3 Tage</span>
                  </div>
                  <div data-testid="search-job-card" data-job-id="ccc333ddd444">
                    <a data-testid="search-job-card-link" href="/de/job/ml-engineer--hamburg--ccc333ddd444">ML Engineer</a>
                    <span data-testid="search-job-card-company-name">Gamma GmbH</span>
                    <span data-testid="job-card-chip-location">Hamburg</span>
                    <span data-testid="job-card-date">Heute</span>
                  </div>
                </body></html>
                """);

        FetchResult result = strategy.fetch(
                context(List.of("Applied AI Engineer", "software engineer")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs())
                .extracting(RawAggregatorJob::externalId)
                .containsExactly("abc123def456", "789abc000111", "ccc333ddd444");
        verify(1, getRequestedFor(urlPathEqualTo("/de/jobs/q/Applied%20AI%20Engineer")));
        verify(1, getRequestedFor(urlPathEqualTo("/de/jobs/q/software%20engineer")));
    }

    @Test
    void fetch_skipsCardWithBlankJobId() {
        stubSearch("Applied%20AI%20Engineer", """
                <html><body>
                  <div data-testid="search-job-card" data-job-id="">
                    <a data-testid="search-job-card-link" href="/de/job/no-id--nowhere">No Id Job</a>
                    <span data-testid="search-job-card-company-name">Ghost GmbH</span>
                  </div>
                  <div data-testid="search-job-card" data-job-id="keep00112233">
                    <a data-testid="search-job-card-link" href="/de/job/real-job--berlin--keep00112233">Real Job</a>
                    <span data-testid="search-job-card-company-name">Keep AG</span>
                    <span data-testid="job-card-chip-location">Berlin</span>
                    <span data-testid="job-card-date">Heute</span>
                  </div>
                </body></html>
                """);

        FetchResult result = strategy.fetch(context(List.of("Applied AI Engineer")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs())
                .singleElement()
                .extracting(RawAggregatorJob::externalId)
                .isEqualTo("keep00112233");
    }

    @Test
    void fetch_noCardsReturnsEmpty() {
        stubSearch("Applied%20AI%20Engineer",
                "<html><body><p>No results found for this search.</p></body></html>");

        FetchResult result = strategy.fetch(context(List.of("Applied AI Engineer")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void fetch_direct403FallsBackToFlaresolverr() throws Exception {
        stubFor(get(urlPathEqualTo("/de/jobs/q/Applied%20AI%20Engineer"))
                .willReturn(aResponse().withStatus(403).withBody("Just a moment...")));
        stubFor(post(urlEqualTo("/v1"))
                .willReturn(ok(flareSolverrOk(TWO_CARDS_HTML))
                        .withHeader("Content-Type", "application/json")));

        strategy = new JobleadsStrategy(WebClient.builder().build(), baseUrl + "/v1");
        FetchResult result = strategy.fetch(context(List.of("Applied AI Engineer")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs())
                .extracting(RawAggregatorJob::externalId)
                .containsExactly("abc123def456", "789abc000111");
        verify(1, getRequestedFor(urlPathEqualTo("/de/jobs/q/Applied%20AI%20Engineer")));
        verify(1, postRequestedFor(urlEqualTo("/v1")));
    }

    private String flareSolverrOk(String html) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "status", "ok",
                "solution", Map.of("status", 200, "response", html)));
    }

    private FetchContext context(List<String> keywords) {
        return FetchContext.forSearch(keywords, List.of(), 100, 3,
                Map.of("url", baseUrl + "/de/jobs/q/{query}?minSalary=80000"));
    }

    private void stubSearch(String encodedQuery, String body) {
        stubFor(get(urlPathEqualTo("/de/jobs/q/" + encodedQuery))
                .willReturn(ok(body).withHeader("Content-Type", "text/html")));
    }
}
