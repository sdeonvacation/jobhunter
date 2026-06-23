package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstaffoStrategyTest {

    // --- Fixtures ---

    private static final String SITEMAP_URL = "https://jobs.instaffo.com/sitemap-jobs.xml";

    private static final String SITEMAP_2EN_1DE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://jobs.instaffo.com/en/job/backend-engineer-abc123456789</loc></url>
              <url><loc>https://jobs.instaffo.com/en/job/frontend-dev-def012345678</loc></url>
              <url><loc>https://jobs.instaffo.com/de/job/german-job-xyz999999999</loc></url>
            </urlset>
            """;

    private static final String SITEMAP_5EN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://jobs.instaffo.com/en/job/job-one-aaa111111111</loc></url>
              <url><loc>https://jobs.instaffo.com/en/job/job-two-bbb222222222</loc></url>
              <url><loc>https://jobs.instaffo.com/en/job/job-three-ccc333333333</loc></url>
              <url><loc>https://jobs.instaffo.com/en/job/job-four-ddd444444444</loc></url>
              <url><loc>https://jobs.instaffo.com/en/job/job-five-eee555555555</loc></url>
            </urlset>
            """;

    private static final String SITEMAP_SINGLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://jobs.instaffo.com/en/job/data-scientist-ghi345678901</loc></url>
            </urlset>
            """;

    private static final String SITEMAP_EMPTY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"/>
            """;

    private static final String JOB_HTML_WITH_SALARY = """
            <!DOCTYPE html><html>
            <head><title>Backend Engineer at Acme GmbH</title></head>
            <body>
            <h1>Backend Engineer</h1>
            <script type="application/ld+json">
            {
              "@type": "JobPosting",
              "title": "Backend Engineer",
              "hiringOrganization": {"name": "Acme GmbH"},
              "datePosted": "2024-06-15",
              "description": "<p>Build APIs for fintech.</p>",
              "jobLocation": [{"address": {"addressLocality": "Berlin"}}]
            }
            </script>
            <p data-variant="body-m">80,000 - 95,000 \u20ac</p>
            </body></html>
            """;

    private static final String JOB_HTML_NO_SALARY = """
            <!DOCTYPE html><html>
            <head><title>Frontend Developer at BetaStart</title></head>
            <body>
            <h1>Frontend Developer</h1>
            <script type="application/ld+json">
            {
              "@type": "JobPosting",
              "title": "Frontend Developer",
              "hiringOrganization": {"name": "BetaStart"},
              "datePosted": "2024-06-10",
              "description": "<p>Build UIs with React.</p>",
              "jobLocation": [{"address": {"addressLocality": "Hamburg"}}]
            }
            </script>
            </body></html>
            """;

    private static final String JOB_HTML_NO_JSONLD = """
            <!DOCTYPE html><html>
            <head><title>Data Scientist at DataCo</title></head>
            <body>
            <h1>Data Scientist</h1>
            <p>Work with data at DataCo</p>
            </body></html>
            """;

    // --- Setup ---

    private WebClient webClient;
    private JobPostingRepository repository;
    private InstaffoStrategy strategy;

    @SuppressWarnings("unchecked")
    private WebClient.RequestHeadersUriSpec<?> requestSpec;
    @SuppressWarnings("unchecked")
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webClient = mock(WebClient.class);
        repository = mock(JobPostingRepository.class);
        requestSpec = mock(WebClient.RequestHeadersUriSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        strategy = new InstaffoStrategy(webClient, repository);

        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestSpec);
        when(requestSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(repository.findExternalIdsBySource(any())).thenReturn(List.of());
    }

    private FetchContext context(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("url", SITEMAP_URL);
        config.put("delayBetweenMs", "0");
        config.put("maxScrapePerRun", "50");
        config.putAll(extra);
        return FetchContext.forSearch(List.of(), List.of(), 50, 5, config);
    }

    // --- Metadata tests ---

    @Test
    @DisplayName("name() returns instaffo")
    void nameIsInstaffo() {
        assertThat(strategy.name()).isEqualTo("instaffo");
    }

    @Test
    @DisplayName("supports() returns false for all AtsTypes")
    void supportsReturnsFalse() {
        for (AtsType type : AtsType.values()) {
            assertThat(strategy.supports(type)).isFalse();
        }
    }

    // --- fetch() tests ---

    @Nested
    @DisplayName("fetch()")
    class FetchTests {

        @Test
        @DisplayName("happy path: 2 EN URLs scraped, 1 DE filtered out")
        void happyPath() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_2EN_1DE))
                    .thenReturn(Mono.just(JOB_HTML_WITH_SALARY))
                    .thenReturn(Mono.just(JOB_HTML_NO_SALARY));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);

            RawAggregatorJob job1 = result.jobs().get(0);
            assertThat(job1.title()).isEqualTo("Backend Engineer");
            assertThat(job1.companyName()).isEqualTo("Acme GmbH");
            assertThat(job1.location()).isEqualTo("Berlin");
            assertThat(job1.salaryMin()).isEqualByComparingTo("80000");
            assertThat(job1.salaryMax()).isEqualByComparingTo("95000");
            assertThat(job1.salaryCurrency()).isEqualTo("EUR");
            assertThat(job1.externalId()).isEqualTo("abc123456789");
            assertThat(job1.applyUrl()).isEqualTo("https://jobs.instaffo.com/en/job/backend-engineer-abc123456789");

            RawAggregatorJob job2 = result.jobs().get(1);
            assertThat(job2.title()).isEqualTo("Frontend Developer");
            assertThat(job2.salaryMin()).isNull();
            assertThat(job2.salaryCurrency()).isNull();
            assertThat(job2.externalId()).isEqualTo("def012345678");
        }

        @Test
        @DisplayName("incremental: 3 already-known jobs skipped, 2 scraped")
        void incrementalSkipsKnownJobs() {
            when(repository.findExternalIdsBySource(JobSource.INSTAFFO))
                    .thenReturn(List.of("aaa111111111", "bbb222222222", "ccc333333333"));
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_5EN))
                    .thenReturn(Mono.just(JOB_HTML_WITH_SALARY))
                    .thenReturn(Mono.just(JOB_HTML_NO_SALARY));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
        }

        @Test
        @DisplayName("rate limit on detail page stops early, returns partial success")
        void rateLimitStopsEarly() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_2EN_1DE))
                    .thenReturn(Mono.just(JOB_HTML_WITH_SALARY))
                    .thenReturn(Mono.error(WebClientResponseException.create(
                            429, "Too Many Requests", null, null, null)));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("rate limit on first detail page with no prior success returns RATE_LIMITED")
        void rateLimitOnFirstDetailPageReturnsRateLimited() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_SINGLE))
                    .thenReturn(Mono.error(WebClientResponseException.create(
                            429, "Too Many Requests", null, null, null)));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("404 on detail page is skipped silently, other jobs returned")
        void notFoundSkipped() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_2EN_1DE))
                    .thenReturn(Mono.error(WebClientResponseException.create(
                            404, "Not Found", null, null, null)))
                    .thenReturn(Mono.just(JOB_HTML_NO_SALARY));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Frontend Developer");
        }

        @Test
        @DisplayName("maxScrapePerRun=1 caps scraping to 1 job")
        void maxScrapePerRunCaps() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_5EN))
                    .thenReturn(Mono.just(JOB_HTML_WITH_SALARY));

            FetchResult result = strategy.fetch(context(Map.of("maxScrapePerRun", "1")));

            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("sitemap fetch failure returns ERROR")
        void sitemapFetchFailure() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.error(new RuntimeException("Connection refused")));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("Sitemap fetch failed");
        }

        @Test
        @DisplayName("sitemap 429 returns RATE_LIMITED")
        void sitemapRateLimited() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.error(WebClientResponseException.create(
                            429, "Too Many Requests", null, null, null)));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("no URL in context returns ERROR")
        void noUrlReturnsError() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 5, Map.of());

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("No URL");
        }

        @Test
        @DisplayName("empty sitemap returns EMPTY")
        void emptySitemapReturnsEmpty() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_EMPTY));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
            assertThat(result.jobs()).isEmpty();
        }

        @Test
        @DisplayName("all jobs already known returns EMPTY without any HTTP detail fetches")
        void allKnownReturnsEmpty() {
            when(repository.findExternalIdsBySource(JobSource.INSTAFFO))
                    .thenReturn(List.of("abc123456789", "def012345678"));
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_2EN_1DE));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("JSON-LD absent: falls back to h1 title and title-tag company")
        void noJsonLdFallback() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_SINGLE))
                    .thenReturn(Mono.just(JOB_HTML_NO_JSONLD));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Data Scientist");
            assertThat(result.jobs().get(0).companyName()).isEqualTo("DataCo");
        }

        @Test
        @DisplayName("multiple jobLocation entries joined with comma")
        void multipleLocationsJoined() {
            String multiLocHtml = """
                    <!DOCTYPE html><html><head><title>Engineer at Corp</title></head><body>
                    <script type="application/ld+json">
                    {
                      "@type": "JobPosting",
                      "title": "Engineer",
                      "hiringOrganization": {"name": "Corp"},
                      "datePosted": "2024-01-01",
                      "description": "desc",
                      "jobLocation": [
                        {"address": {"addressLocality": "Berlin"}},
                        {"address": {"addressLocality": "Munich"}}
                      ]
                    }
                    </script>
                    </body></html>
                    """;
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_SINGLE))
                    .thenReturn(Mono.just(multiLocHtml));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).location()).isEqualTo("Berlin, Munich");
        }

        @Test
        @DisplayName("postedDate parsed correctly from ISO date string")
        void postedDateParsed() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_SINGLE))
                    .thenReturn(Mono.just(JOB_HTML_WITH_SALARY));

            FetchResult result = strategy.fetch(context(Map.of()));

            assertThat(result.jobs().get(0).postedDate()).isEqualTo("2024-06-15");
        }
    }

    // --- extractExternalId() tests ---

    @Nested
    @DisplayName("extractExternalId()")
    class ExternalIdTests {

        @Test
        @DisplayName("extracts 12-char hex suffix from URL slug")
        void extractsHexId() {
            String url = "https://jobs.instaffo.com/en/job/backend-engineer-abc123456789";
            assertThat(strategy.extractExternalId(url)).isEqualTo("abc123456789");
        }

        @Test
        @DisplayName("falls back to hashCode when no 12-char hex suffix present")
        void fallsBackToHashCode() {
            String url = "https://jobs.instaffo.com/en/job/no-id-here";
            String id = strategy.extractExternalId(url);
            assertThat(id).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("hex pattern is case-sensitive (lowercase only)")
        void hexPatternLowercaseOnly() {
            // Uppercase hex should not match — falls back to hashCode
            String url = "https://jobs.instaffo.com/en/job/some-job-ABC123456789";
            String id = strategy.extractExternalId(url);
            // Should not be "ABC123456789" since pattern only matches [a-f0-9]
            assertThat(id).isNotEqualTo("ABC123456789");
        }
    }
}
