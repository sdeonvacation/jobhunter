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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestApiStrategyTest {

    private WebClient webClient;
    private RestApiStrategy strategy;

    @SuppressWarnings("unchecked")
    private WebClient.RequestHeadersUriSpec<?> requestSpec;
    @SuppressWarnings("unchecked")
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webClient = mock(WebClient.class);
        requestSpec = mock(WebClient.RequestHeadersUriSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        strategy = new RestApiStrategy(webClient);

        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestSpec);
        when(requestSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("name() returns rest-api")
    void nameReturnsRestApi() {
        assertThat(strategy.name()).isEqualTo("rest-api");
    }

    @Test
    @DisplayName("supports() returns false for all AtsTypes")
    void supportsReturnsFalse() {
        for (AtsType type : AtsType.values()) {
            assertThat(strategy.supports(type)).isFalse();
        }
    }

    @Nested
    @DisplayName("fetch()")
    class FetchTests {

        @Test
        @DisplayName("returns error when no URL in context")
        void errorWhenNoUrl() {
            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3, Map.of());

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("No URL configured");
        }

        @Test
        @DisplayName("parses jobs from data array wrapper")
        void parsesDataArrayWrapper() {
            String json = """
                    {
                      "data": [
                        {"title": "Backend Dev", "company_name": "TechCo", "location": "Berlin", "description": "Java work", "url": "https://arbeitnow.com/jobs/123", "slug": "backend-dev-techco", "created_at": "2024-06-01"},
                        {"title": "Frontend Dev", "company_name": "WebCo", "location": "Munich", "description": "React", "url": "https://arbeitnow.com/jobs/456", "slug": "frontend-dev-webco", "created_at": "2024-06-02"}
                      ],
                      "links": {"next": null}
                    }
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(json));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://www.arbeitnow.com/api/job-board-api"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Backend Dev");
            assertThat(result.jobs().get(0).companyName()).isEqualTo("TechCo");
            assertThat(result.jobs().get(0).location()).isEqualTo("Berlin");
            assertThat(result.jobs().get(0).externalId()).isEqualTo("backend-dev-techco");
            assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 6, 1));
            assertThat(result.jobs().get(0).rawJson()).isNotNull();
        }

        @Test
        @DisplayName("parses jobs from root array")
        void parsesRootArray() {
            String json = """
                    [
                      {"title": "Dev", "company_name": "Co", "location": "Berlin", "description": "Work", "url": "https://example.com/1", "created_at": "2024-01-15T10:30:00"}
                    ]
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(json));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        }

        @Test
        @DisplayName("follows pagination via links.next")
        void followsPagination() {
            String page1 = """
                    {
                      "data": [{"title": "Job1", "company_name": "Co1", "location": "Berlin", "description": "d1", "url": "https://ex.com/1"}],
                      "links": {"next": "https://api.example.com/jobs?page=2"}
                    }
                    """;
            String page2 = """
                    {
                      "data": [{"title": "Job2", "company_name": "Co2", "location": "Munich", "description": "d2", "url": "https://ex.com/2"}],
                      "links": {"next": null}
                    }
                    """;

            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.just(page1))
                    .thenReturn(Mono.just(page2));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Job1");
            assertThat(result.jobs().get(1).title()).isEqualTo("Job2");
        }

        @Test
        @DisplayName("respects maxResults across pages")
        void respectsMaxResults() {
            String page1 = """
                    {
                      "data": [
                        {"title": "Job1", "company_name": "Co1", "location": "B", "description": "d", "url": "https://ex.com/1"},
                        {"title": "Job2", "company_name": "Co2", "location": "B", "description": "d", "url": "https://ex.com/2"}
                      ],
                      "links": {"next": "https://api.example.com/jobs?page=2"}
                    }
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(page1));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 1, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.jobs()).hasSize(1);
        }

        @Test
        @DisplayName("returns empty when response is blank")
        void emptyWhenBlankResponse() {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(""));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("returns empty when data array is empty")
        void emptyWhenNoJobs() {
            String json = """
                    {"data": [], "links": {"next": null}}
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(json));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("returns error when WebClient throws")
        void errorOnException() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.error(new RuntimeException("Timeout")));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("Timeout");
        }

        @Test
        @DisplayName("skips entries with null title")
        void skipsNullTitle() {
            String json = """
                    {
                      "data": [
                        {"title": null, "company_name": "Co", "location": "Berlin", "description": "d", "url": "https://ex.com/1"},
                        {"title": "Valid Job", "company_name": "Co2", "location": "Munich", "description": "d2", "url": "https://ex.com/2"}
                      ]
                    }
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(json));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Valid Job");
        }

        @Test
        @DisplayName("handles date with ISO datetime format")
        void handlesIsoDatetime() {
            String json = """
                    {"data": [{"title": "Job", "company_name": "Co", "location": "B", "description": "d", "url": "https://ex.com/1", "created_at": "2024-03-20T14:30:00Z"}]}
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(json));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 50, 3,
                    Map.of("url", "https://api.example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 3, 20));
        }
    }
}
