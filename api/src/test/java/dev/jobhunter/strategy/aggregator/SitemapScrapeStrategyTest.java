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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SitemapScrapeStrategyTest {

    // ─── Minimal concrete subclass ────────────────────────────────────────────

    static class TestSitemapStrategy extends SitemapScrapeStrategy {

        TestSitemapStrategy(WebClient webClient, JobPostingRepository repo) {
            super(webClient, repo);
        }

        @Override
        public String name() { return "test-sitemap"; }

        @Override
        protected Pattern urlFilterPattern() {
            return Pattern.compile("https://example\\.com/jobs/\\d+");
        }

        @Override
        protected String extractExternalId(String url) {
            String[] parts = url.split("/");
            return parts[parts.length - 1];
        }

        @Override
        protected Optional<RawAggregatorJob> parsePage(String html, String url, String externalId) {
            if (html.contains("SKIP")) return Optional.empty();
            return Optional.of(new RawAggregatorJob(
                    externalId,
                    "Job " + externalId,
                    "TestCo",
                    "Berlin",
                    null,
                    url,
                    null,
                    null,
                    null,
                    null,
                    null));
        }

        @Override
        protected JobSource jobSource() { return JobSource.ARBEITNOW; }

        @Override
        protected int defaultDelayMs() { return 0; } // no delay in tests
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private WebClient webClient;
    private JobPostingRepository jobPostingRepository;
    private TestSitemapStrategy strategy;

    @SuppressWarnings("unchecked")
    private WebClient.RequestHeadersUriSpec<?> requestSpec;
    @SuppressWarnings("unchecked")
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webClient = mock(WebClient.class);
        jobPostingRepository = mock(JobPostingRepository.class);
        strategy = new TestSitemapStrategy(webClient, jobPostingRepository);

        requestSpec = mock(WebClient.RequestHeadersUriSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestSpec);
        when(requestSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
    }

    // ─── Contract tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("name() returns subclass value")
    void nameReturnsSubclassValue() {
        assertThat(strategy.name()).isEqualTo("test-sitemap");
    }

    @Test
    @DisplayName("supports() always returns false")
    void supportsAlwaysFalse() {
        for (AtsType type : AtsType.values()) {
            assertThat(strategy.supports(type)).isFalse();
        }
    }

    // ─── fetch() — guard clauses ──────────────────────────────────────────────

    @Nested
    @DisplayName("fetch() guard clauses")
    class GuardClauseTests {

        @Test
        @DisplayName("returns ERROR when no URL in context config")
        void errorWhenNoUrl() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3, Map.of());

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("No URL configured");
        }

        @Test
        @DisplayName("returns ERROR when sitemap response is empty")
        void errorWhenSitemapEmpty() {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(""));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("Empty sitemap response");
        }
    }

    // ─── fetch() — sitemap HTTP errors ───────────────────────────────────────

    @Nested
    @DisplayName("fetch() sitemap HTTP errors")
    class SitemapHttpErrorTests {

        @Test
        @DisplayName("returns RATE_LIMITED when sitemap fetch returns 429")
        void rateLimitedOn429() {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(
                    WebClientResponseException.create(429, "Too Many Requests", null, null, null)));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("returns ERROR when sitemap fetch returns 500")
        void errorOn500() {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(
                    WebClientResponseException.create(500, "Internal Server Error", null, null, null)));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("Sitemap fetch failed");
        }

        @Test
        @DisplayName("returns ERROR on non-HTTP exception fetching sitemap")
        void errorOnGenericException() {
            when(responseSpec.bodyToMono(String.class)).thenReturn(
                    Mono.error(new RuntimeException("Connection refused")));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.ERROR);
        }
    }

    // ─── fetch() — filtering and dedup ───────────────────────────────────────

    @Nested
    @DisplayName("fetch() URL filtering and dedup")
    class FilterAndDedupTests {

        /** Sitemap with 2 matching job URLs + 1 non-matching /about URL. */
        private static final String SITEMAP_WITH_FILTER = """
                <?xml version="1.0"?>
                <urlset>
                  <url><loc>https://example.com/jobs/1</loc></url>
                  <url><loc>https://example.com/jobs/2</loc></url>
                  <url><loc>https://example.com/about</loc></url>
                </urlset>
                """;

        @Test
        @DisplayName("returns EMPTY when all matching URLs are already known")
        void emptyWhenAllKnown() {
            // only sitemap is fetched — no pages, so single thenReturn suffices
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(SITEMAP_WITH_FILTER));
            when(jobPostingRepository.findExternalIdsBySource(JobSource.ARBEITNOW))
                    .thenReturn(List.of("1", "2"));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("skips non-matching URLs (about page filtered out)")
        void filtersNonMatchingUrls() {
            // sitemap first, then page responses for jobs/1 and jobs/2
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_WITH_FILTER))
                    .thenReturn(Mono.just("<html>job 1</html>"))
                    .thenReturn(Mono.just("<html>job 2</html>"));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
        }

        @Test
        @DisplayName("skips known externalIds, scrapes only new")
        void skipsKnownIds() {
            // job 1 already known — only job 2 scraped
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_WITH_FILTER))
                    .thenReturn(Mono.just("<html>job 2</html>"));
            when(jobPostingRepository.findExternalIdsBySource(JobSource.ARBEITNOW))
                    .thenReturn(List.of("1"));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).externalId()).isEqualTo("2");
        }

        @Test
        @DisplayName("proceeds without dedup when repository throws")
        void proceedsWhenRepoThrows() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(SITEMAP_WITH_FILTER))
                    .thenReturn(Mono.just("<html>job 1</html>"))
                    .thenReturn(Mono.just("<html>job 2</html>"));
            when(jobPostingRepository.findExternalIdsBySource(any()))
                    .thenThrow(new RuntimeException("DB down"));

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            // scrapes both (no dedup available)
            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
        }
    }

    // ─── fetch() — maxScrapePerRun cap ────────────────────────────────────────

    @Nested
    @DisplayName("fetch() maxScrapePerRun cap")
    class MaxScrapeCapTests {

        @Test
        @DisplayName("caps scrapes at maxScrapePerRun config value")
        void capsAtMax() {
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><urlset>");
            for (int i = 1; i <= 10; i++) {
                sb.append("<url><loc>https://example.com/jobs/").append(i).append("</loc></url>");
            }
            sb.append("</urlset>");

            // sitemap + 3 page responses (cap = 3)
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sb.toString()))
                    .thenReturn(Mono.just("<html>p1</html>"))
                    .thenReturn(Mono.just("<html>p2</html>"))
                    .thenReturn(Mono.just("<html>p3</html>"));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml", "maxScrapePerRun", "3"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
        }
    }

    // ─── fetch() — detail page handling ──────────────────────────────────────

    @Nested
    @DisplayName("fetch() detail page handling")
    class DetailPageTests {

        @Test
        @DisplayName("skips page when parsePage returns empty")
        void skipsWhenParsePageEmpty() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1")))
                    .thenReturn(Mono.just("SKIP this page"));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("skips page on 404 without counting as error")
        void skips404Pages() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1", "2")))
                    .thenReturn(Mono.error(
                            WebClientResponseException.create(404, "Not Found", null, null, null)))
                    .thenReturn(Mono.just("<html>job 2</html>"));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).externalId()).isEqualTo("2");
        }

        @Test
        @DisplayName("skips page on 410 Gone without counting as error")
        void skips410Pages() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1")))
                    .thenReturn(Mono.error(
                            WebClientResponseException.create(410, "Gone", null, null, null)));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("returns RATE_LIMITED when page returns 429 and no jobs scraped yet")
        void rateLimitedOnPageWith429AndNoJobs() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1")))
                    .thenReturn(Mono.error(
                            WebClientResponseException.create(429, "Too Many Requests", null, null, null)));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("returns SUCCESS with partial jobs when 429 occurs after some scraped")
        void partialSuccessBeforeRateLimit() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1", "2")))
                    .thenReturn(Mono.just("<html>job 1</html>"))
                    .thenReturn(Mono.error(
                            WebClientResponseException.create(429, "Too Many Requests", null, null, null)));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("skips empty page body without counting as error")
        void skipsEmptyPageBody() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1")))
                    .thenReturn(Mono.just(""));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            assertThat(strategy.fetch(ctx).status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("counts 503 errors but continues scraping remaining pages")
        void countsErrorsButContinues() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1", "2")))
                    .thenReturn(Mono.error(
                            WebClientResponseException.create(503, "Service Unavailable", null, null, null)))
                    .thenReturn(Mono.just("<html>job 2</html>"));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).externalId()).isEqualTo("2");
        }

        @Test
        @DisplayName("counts generic exceptions and continues scraping")
        void countsGenericExceptionsAndContinues() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(sitemap("1", "2")))
                    .thenReturn(Mono.error(new RuntimeException("timeout")))
                    .thenReturn(Mono.just("<html>job 2</html>"));
            when(jobPostingRepository.findExternalIdsBySource(any())).thenReturn(List.of());

            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://example.com/sitemap.xml"));

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
        }
    }

    // ─── parseIntConfig ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseIntConfig")
    class ParseIntConfigTests {

        @Test
        @DisplayName("uses default when key absent")
        void usesDefault() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3, Map.of());
            assertThat(strategy.parseIntConfig(ctx, "missing", 99)).isEqualTo(99);
        }

        @Test
        @DisplayName("parses numeric string")
        void parsesNumericString() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("myKey", "42"));
            assertThat(strategy.parseIntConfig(ctx, "myKey", 0)).isEqualTo(42);
        }

        @Test
        @DisplayName("falls back to default on non-numeric value")
        void fallsBackOnNonNumeric() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("myKey", "abc"));
            assertThat(strategy.parseIntConfig(ctx, "myKey", 7)).isEqualTo(7);
        }
    }

    // ─── resolveUrl ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveUrl returns null when key absent")
    void resolveUrlNullWhenAbsent() {
        FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3, Map.of());
        assertThat(strategy.resolveUrl(ctx)).isNull();
    }

    @Test
    @DisplayName("resolveUrl returns string value from config")
    void resolveUrlReturnsValue() {
        FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                Map.of("url", "https://example.com/sitemap.xml"));
        assertThat(strategy.resolveUrl(ctx)).isEqualTo("https://example.com/sitemap.xml");
    }

    // ─── elapsed ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("elapsed returns non-negative duration")
    void elapsedNonNegative() {
        java.time.Instant start = java.time.Instant.now();
        assertThat(strategy.elapsed(start)).isNotNull().isGreaterThanOrEqualTo(java.time.Duration.ZERO);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Build a minimal sitemap XML containing job URLs for the given IDs. */
    private static String sitemap(String... ids) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><urlset>");
        for (String id : ids) {
            sb.append("<url><loc>https://example.com/jobs/").append(id).append("</loc></url>");
        }
        sb.append("</urlset>");
        return sb.toString();
    }
}
