package dev.jobhunter.strategy.aggregator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class WorkInFinlandStrategyTest {

    private WireMockServer wireMockServer;
    private WorkInFinlandStrategy strategy;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        int port = wireMockServer.port();
        baseUrl = "http://localhost:" + port + "/api/jobs/";
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + port).build();
        strategy = new WorkInFinlandStrategy(webClient);
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
        config.put("url", baseUrl);
        config.putAll(extra);
        return FetchContext.forSearch(List.of(), List.of(), maxResults, 10, config);
    }

    private String envelope(int totalJobs, int totalPages, String jobsJson) {
        return """
                {"totalJobs":%d,"totalPages":%d,"categories":[{"id":"ict","count":453,"name":"Technology"}],"cities":[{"name":"Helsinki","count":220,"id":"helsinki"}],"jobs":[%s]}
                """.formatted(totalJobs, totalPages, jobsJson);
    }

    private String jobJson(String title, String employer, String city, String externalUrl) {
        return """
                {"title":"%s","employer":{"name":"%s","city":"%s","imageUrl":"https://img.example/y.png"},"expireDate":"2026-08-27T21:19:47.834Z","externalUrl":"%s"}
                """.formatted(title, employer, city, externalUrl);
    }

    private String nJobs(int count, String prefix) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> jobJson(prefix + " Job " + i, "Co " + i, "Helsinki", "https://www.jobly.fi/en/job/" + prefix + "-" + i))
                .collect(Collectors.joining(","));
    }

    private void stubPage(int page, int totalPages, String jobsJson, String category) {
        com.github.tomakehurst.wiremock.client.MappingBuilder builder = get(urlPathEqualTo("/api/jobs/"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .withQueryParam("limit", equalTo("50"));
        if (category != null) {
            builder = builder.withQueryParam("category", equalTo(category));
        } else {
            builder = builder.withQueryParam("category", absent());
        }
        wireMockServer.stubFor(builder.willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(envelope(countJobs(jobsJson), totalPages, jobsJson))));
    }

    private int countJobs(String jobsJson) {
        if (jobsJson == null || jobsJson.isBlank()) {
            return 0;
        }
        return (int) jobsJson.chars().filter(c -> c == '{').count();
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
    @DisplayName("name() returns workinfinland")
    void nameReturnsWorkInFinland() {
        assertThat(strategy.name()).isEqualTo("workinfinland");
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
        @DisplayName("happy path single category: 2 pages, totalPages=2, 3 jobs total")
        void happyPathSingleCategory() {
            stubPage(1, 2, jobJson("Senior Engineer", "Acme Oy", "Espoo",
                    "https://www.jobly.fi/en/job/example-1")
                    + "," + jobJson("Backend Dev", "Beta Oy", "Helsinki",
                    "https://www.jobly.fi/en/job/example-2"), "ict");
            stubPage(2, 2, jobJson("Data Engineer", "Gamma Oy", "Tampere",
                    "https://www.jobly.fi/en/job/example-3"), "ict");

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            RawAggregatorJob job0 = result.jobs().get(0);
            assertThat(job0.title()).isEqualTo("Senior Engineer");
            assertThat(job0.companyName()).isEqualTo("Acme Oy");
            assertThat(job0.location()).isEqualTo("Espoo");
            assertThat(job0.applyUrl()).isEqualTo("https://www.jobly.fi/en/job/example-1");
            assertThat(job0.externalId()).isEqualTo("https://www.jobly.fi/en/job/example-1");
        }

        @Test
        @DisplayName("pagination stops at totalPages: page1=50, page2=25 → 75 jobs, no page3")
        void paginationStopsAtTotalPages() {
            stubPage(1, 2, nJobs(50, "p1"), "ict");
            stubPage(2, 2, nJobs(25, "p2"), "ict");

            FetchResult result = strategy.fetch(context(1000, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(75);
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/api/jobs/"))
                    .withQueryParam("page", equalTo("3")));
        }

        @Test
        @DisplayName("maxResults cap: API has 1000 jobs, maxResults=50 → ≤50 returned, no page2")
        void maxResultsCap() {
            stubPage(1, 20, nJobs(50, "cap"), "ict");
            // page2 intentionally not stubbed; if requested the test would fail on 404

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(50);
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/api/jobs/"))
                    .withQueryParam("page", equalTo("2")));
        }

        @Test
        @DisplayName("multi-category fan-out + dedup: overlapping externalUrl deduped to one each")
        void multiCategoryDedup() {
            String shared = jobJson("Shared Role", "Dup Co", "Oulu", "https://www.jobly.fi/en/job/shared-1");
            String aOnly = jobJson("Alpha Role", "A Co", "Oulu", "https://www.jobly.fi/en/job/alpha-1");
            String cOnly = jobJson("Charlie Role", "C Co", "Oulu", "https://www.jobly.fi/en/job/charlie-1");

            stubPage(1, 1, shared + "," + aOnly, "ict");
            stubPage(1, 1, shared + "," + cOnly, "engineering");

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict,engineering")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            List<String> urls = result.jobs().stream()
                    .map(RawAggregatorJob::applyUrl)
                    .collect(Collectors.toList());
            assertThat(urls).containsExactlyInAnyOrder(
                    "https://www.jobly.fi/en/job/shared-1",
                    "https://www.jobly.fi/en/job/alpha-1",
                    "https://www.jobly.fi/en/job/charlie-1");
        }

        @Test
        @DisplayName("no categories → fetch all (no category query param in request)")
        void noCategoriesFetchesAll() {
            stubPage(1, 1, jobJson("Solo Role", "Solo Co", "Turku",
                    "https://www.jobly.fi/en/job/solo-1"), null);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);

            boolean anyCategoryParam = wireMockServer.getAllServeEvents().stream()
                    .anyMatch(e -> e.getRequest().getUrl() != null
                            && e.getRequest().getUrl().contains("category="));
            assertThat(anyCategoryParam).isFalse();
        }

        @Test
        @DisplayName("externalId ≤255 equals externalUrl; >255 → 64-char SHA-256 hex")
        void externalIdDerivation() throws Exception {
            String shortUrl = "https://www.jobly.fi/en/job/short-1";
            String longUrl = "https://www.jobly.fi/en/job/" + "a".repeat(300);
            stubPage(1, 1,
                    jobJson("Short", "Co", "Helsinki", shortUrl) + ","
                            + jobJson("Long", "Co", "Helsinki", longUrl), null);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            RawAggregatorJob shortJob = result.jobs().stream()
                    .filter(j -> j.title().equals("Short")).findFirst().orElseThrow();
            RawAggregatorJob longJob = result.jobs().stream()
                    .filter(j -> j.title().equals("Long")).findFirst().orElseThrow();

            assertThat(shortJob.externalId()).isEqualTo(shortUrl);
            assertThat(longJob.externalId()).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(longJob.externalId()).isEqualTo(sha256Hex(longUrl));
        }

        @Test
        @DisplayName("null title or null externalUrl → job skipped, others returned")
        void nullTitleOrUrlSkipped() {
            String noTitle = """
                    {"title":null,"employer":{"name":"NoTitle Co","city":"Helsinki","imageUrl":"https://x/y.png"},"expireDate":"2026-08-27T21:19:47.834Z","externalUrl":"https://www.jobly.fi/en/job/notitle-1"}
                    """;
            String noUrl = """
                    {"title":"No Url Role","employer":{"name":"NoUrl Co","city":"Helsinki","imageUrl":"https://x/y.png"},"expireDate":"2026-08-27T21:19:47.834Z","externalUrl":null}
                    """;
            String valid = jobJson("Valid Role", "Valid Co", "Helsinki",
                    "https://www.jobly.fi/en/job/valid-1");
            stubPage(1, 1, noTitle + "," + noUrl + "," + valid, null);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Valid Role");
        }

        @Test
        @DisplayName("API 500 → FetchResult.error")
        void api500Error() {
            wireMockServer.stubFor(get(urlPathEqualTo("/api/jobs/"))
                    .willReturn(aResponse().withStatus(500).withBody("boom")));

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("API 429 → FetchResult.rateLimited")
        void api429RateLimited() {
            wireMockServer.stubFor(get(urlPathEqualTo("/api/jobs/"))
                    .willReturn(aResponse().withStatus(429).withBody("slow down")));

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        }

        @Test
        @DisplayName("malformed JSON → FetchResult.error")
        void malformedJson() {
            wireMockServer.stubFor(get(urlPathEqualTo("/api/jobs/"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{not valid json")));

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        }

        @Test
        @DisplayName("empty jobs array, totalJobs=0 → FetchResult.empty")
        void emptyJobs() {
            stubPage(1, 1, "", null);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("late-page failure (page1 ok, page2 500) → partial jobs from page1 returned")
        void latePageFailureReturnsPartial() {
            stubPage(1, 2, jobJson("Page1 Role A", "Co", "Helsinki",
                    "https://www.jobly.fi/en/job/p1a")
                    + "," + jobJson("Page1 Role B", "Co", "Helsinki",
                    "https://www.jobly.fi/en/job/p1b"), "ict");
            wireMockServer.stubFor(get(urlPathEqualTo("/api/jobs/"))
                    .withQueryParam("page", equalTo("2"))
                    .withQueryParam("category", equalTo("ict"))
                    .willReturn(aResponse().withStatus(500).withBody("boom")));

            FetchResult result = strategy.fetch(context(50, Map.of("categories", "ict")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Page1 Role A");
        }

        @Test
        @DisplayName("mapped job has null description, postedDate, and salary fields")
        void nullOptionalFields() {
            stubPage(1, 1, jobJson("Nulls Role", "Co", "Helsinki",
                    "https://www.jobly.fi/en/job/nulls-1"), null);

            FetchResult result = strategy.fetch(context(50, Map.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            RawAggregatorJob job = result.jobs().get(0);
            assertThat(job.description()).isNull();
            assertThat(job.postedDate()).isNull();
            assertThat(job.salaryMin()).isNull();
            assertThat(job.salaryMax()).isNull();
            assertThat(job.salaryCurrency()).isNull();
            assertThat(job.rawJson()).isNotNull().contains("Nulls Role");
        }

        @Test
        @DisplayName("missing url config → FetchResult.error")
        void missingUrlConfig() {
            FetchContext ctx = FetchContext.forSearch(List.of(), List.of(), 50, 10, Map.of());

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("url");
        }
    }
}
