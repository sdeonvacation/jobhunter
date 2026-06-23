package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltInEuropeStrategyTest {

    private WebClient webClient;
    private BuiltInEuropeStrategy strategy;

    @SuppressWarnings("unchecked")
    private WebClient.RequestBodyUriSpec postSpec;
    @SuppressWarnings("unchecked")
    private WebClient.RequestBodySpec bodySpec;
    @SuppressWarnings("unchecked")
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webClient = mock(WebClient.class);
        postSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        strategy = new BuiltInEuropeStrategy(webClient);

        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }

    private FetchContext context(int maxResults, List<String> keywords) {
        return FetchContext.forSearch(keywords, List.of(), maxResults, 3, Map.of("url", "https://api.builtineurope.com/search"));
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private String pageWith(String... jobJsons) {
        String jobs = String.join(",", jobJsons);
        return "{\"results\":[" + jobs + "]}";
    }

    private String emptyPage() {
        return "{\"results\":[]}";
    }

    private String jobJson(String id, String title, String company, String location, String applyUrl) {
        return """
                {"id":"%s","title_raw":"%s","company_display_name":"%s","location_name":"%s","posting_url":"%s","first_seen":1719100800}
                """.formatted(id, title, company, location, applyUrl);
    }

    /** Build N distinct job JSON entries with sequential ids. */
    private String[] nJobs(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> jobJson("id-" + i, "Job " + i, "Co " + i, "Berlin", "https://ex.com/" + i))
                .toArray(String[]::new);
    }

    // ── basic contract ────────────────────────────────────────────────────────

    @Test
    @DisplayName("name() returns builtineurope")
    void nameReturnsBuiltinEurope() {
        assertThat(strategy.name()).isEqualTo("builtineurope");
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
        @DisplayName("happy path: page1 returns 3 jobs, page2 empty → 3 jobs with correct mapping")
        void happyPathSingleKeyword() {
            String page1 = pageWith(
                    jobJson("j1", "Backend Dev", "TechCorp", "Berlin", "https://builtineurope.com/j1"),
                    jobJson("j2", "Frontend Dev", "WebCo", "Munich", "https://builtineurope.com/j2"),
                    jobJson("j3", "DevOps Eng", "CloudCo", "Hamburg", "https://builtineurope.com/j3")
            );

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page1))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            assertThat(result.jobs().get(0).title()).isEqualTo("Backend Dev");
            assertThat(result.jobs().get(0).companyName()).isEqualTo("TechCorp");
            assertThat(result.jobs().get(0).location()).isEqualTo("Berlin");
            assertThat(result.jobs().get(0).externalId()).isEqualTo("j1");
            assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://builtineurope.com/j1");
        }

        @Test
        @DisplayName("pagination stops on empty page: page1=100 jobs, page2=empty → 100 jobs")
        void paginationStopsOnEmptyPage() {
            String page1 = pageWith(nJobs(100));

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page1))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(500, List.of("java")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(100);
        }

        @Test
        @DisplayName("maxResults cap: page1=100 jobs, maxResults=50 → 50 jobs returned")
        void maxResultsCap() {
            String page1 = pageWith(nJobs(100));

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page1));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.jobs()).hasSize(50);
        }

        @Test
        @DisplayName("multi-keyword dedup: keywords a+b share id:2 → 3 unique jobs total")
        void multiKeywordDedup() {
            String pageA = pageWith(
                    jobJson("id-1", "Job 1", "Co1", "Berlin", "https://ex.com/1"),
                    jobJson("id-2", "Job 2", "Co2", "Munich", "https://ex.com/2")
            );
            String pageB = pageWith(
                    jobJson("id-2", "Job 2", "Co2", "Munich", "https://ex.com/2"),
                    jobJson("id-3", "Job 3", "Co3", "Hamburg", "https://ex.com/3")
            );

            // Each page has 2 jobs < PAGE_SIZE(100), so pagination stops after page 1 per keyword
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(pageA))   // keyword "a" page 1
                    .thenReturn(Mono.just(pageB));  // keyword "b" page 1

            FetchResult result = strategy.fetch(context(50, List.of("a", "b")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(3);
            List<String> ids = result.jobs().stream()
                    .map(j -> j.externalId())
                    .collect(Collectors.toList());
            assertThat(ids).containsExactlyInAnyOrder("id-1", "id-2", "id-3");
        }

        @Test
        @DisplayName("empty keywords list: single request with query='' works")
        void emptyKeywordsList() {
            String page1 = pageWith(jobJson("j1", "Some Job", "Co", "Berlin", "https://ex.com/1"));

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page1))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(50, List.of()));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("no URL in context → FetchResult.error")
        void noUrlInContext() {
            FetchContext ctx = FetchContext.forSearch(List.of("java"), List.of(), 50, 3, Map.of());

            FetchResult result = strategy.fetch(ctx);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("HTTP 500 (WebClient throws) → FetchResult.error")
        void http500Error() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.error(new RuntimeException("500 Internal Server Error")));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("500 Internal Server Error");
        }

        @Test
        @DisplayName("malformed JSON → FetchResult.error")
        void malformedJson() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just("{not valid json"));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        }

        @Test
        @DisplayName("null title_raw job skipped, valid jobs still returned")
        void nullTitleSkipped() {
            String jobNoTitle = """
                    {"id":"skip-me","title_raw":null,"company_display_name":"Co","location_name":"Berlin","posting_url":"https://ex.com/x","first_seen":1719100800}
                    """;
            String page = pageWith(
                    jobNoTitle,
                    jobJson("keep-me", "Valid Job", "Co2", "Munich", "https://ex.com/v")
            );

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Valid Job");
        }

        @Test
        @DisplayName("salary mapping: min=50000, max=80000, currency=EUR → BigDecimal values")
        void salaryMapping() {
            String jobWithSalary = """
                    {"id":"s1","title_raw":"Engineer","company_display_name":"Co","location_name":"Berlin",
                    "posting_url":"https://ex.com/s1","first_seen":1719100800,
                    "salary_min":50000,"salary_max":80000,"salary_currency":"EUR"}
                    """;
            String page = pageWith(jobWithSalary);

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).salaryMin()).isEqualByComparingTo(new BigDecimal("50000"));
            assertThat(result.jobs().get(0).salaryMax()).isEqualByComparingTo(new BigDecimal("80000"));
            assertThat(result.jobs().get(0).salaryCurrency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("first_seen epoch 1719100800 → postedDate 2024-06-23")
        void firstSeenEpochToDate() {
            // 1719100800 == 2024-06-23T00:00:00Z
            String page = pageWith(jobJson("j1", "Job", "Co", "Berlin", "https://ex.com/j1"));

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 6, 23));
        }

        @Test
        @DisplayName("skills array → description joined with ', '")
        void skillsAsDescription() {
            String jobWithSkills = """
                    {"id":"sk1","title_raw":"Dev","company_display_name":"Co","location_name":"Berlin",
                    "posting_url":"https://ex.com/sk1","first_seen":1719100800,
                    "skills":["Java","Spring Boot"]}
                    """;
            String page = pageWith(jobWithSkills);

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page))
                    .thenReturn(Mono.just(emptyPage()));

            FetchResult result = strategy.fetch(context(50, List.of("java")));

            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).description()).isEqualTo("Java, Spring Boot");
        }
    }
}
