package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.model.enums.ExtractionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiAggregatorStrategyTest {

    private WebClient webClient;
    private AiProvider aiProvider;
    private AiAggregatorStrategy strategy;

    @SuppressWarnings("unchecked")
    private WebClient.RequestHeadersUriSpec<?> requestSpec;
    @SuppressWarnings("unchecked")
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webClient = mock(WebClient.class);
        aiProvider = mock(AiProvider.class);
        requestSpec = mock(WebClient.RequestHeadersUriSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        strategy = new AiAggregatorStrategy(webClient, aiProvider);

        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) requestSpec);
        when(requestSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(aiProvider.isAvailable()).thenReturn(true);
    }

    @Test
    @DisplayName("name() returns ai")
    void nameReturnsAi() {
        assertThat(strategy.name()).isEqualTo("ai");
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
            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3, Map.of());

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("No URL configured");
        }

        @Test
        @DisplayName("returns error when AI provider unavailable")
        void errorWhenAiUnavailable() {
            when(aiProvider.isAvailable()).thenReturn(false);
            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("AI provider not available");
        }

        @Test
        @DisplayName("returns empty when HTML is blank")
        void emptyWhenBlankHtml() {
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(""));
            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com/jobs"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("extracts jobs from AI response")
        void extractsJobsFromAi() {
            String html = "<html><body><h1>Jobs</h1></body></html>";
            String aiResponse = """
                    [
                      {"title": "Backend Engineer", "companyName": "StartupCo", "location": "Berlin", "description": "Build APIs", "applyUrl": "https://startupco.com/apply/123"},
                      {"title": "Frontend Dev", "companyName": "TechGmbH", "location": "Remote", "description": "React work", "applyUrl": "https://techgmbh.com/jobs/456"}
                    ]
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generate(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://berlinstartupjobs.com/engineering/"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer");
            assertThat(result.jobs().get(0).companyName()).isEqualTo("StartupCo");
            assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://startupco.com/apply/123");
            assertThat(result.jobs().get(0).externalId()).isNotNull().isNotBlank();
            assertThat(result.jobs().get(1).title()).isEqualTo("Frontend Dev");
            assertThat(result.jobs().get(1).externalId()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("generates deterministic externalId from content")
        void generatesDeterministicExternalId() {
            String html = "<html><body>content</body></html>";
            String aiResponse = """
                    [{"title": "Dev", "companyName": "Co", "location": "Berlin", "description": "Code", "applyUrl": "https://co.com/1"}]
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generate(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result1 = strategy.fetch(context);

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generate(anyString(), anyString())).thenReturn(aiResponse);

            FetchResult result2 = strategy.fetch(context);

            // Same content produces same ID (enables dedup)
            assertThat(result1.jobs().get(0).externalId())
                    .isEqualTo(result2.jobs().get(0).externalId());
        }

        @Test
        @DisplayName("handles AI response with markdown fences")
        void handlesMarkdownFences() {
            String html = "<html><body>content</body></html>";
            String aiResponse = """
                    ```json
                    [{"title": "Dev", "companyName": "Co", "location": "Berlin", "description": "Code", "applyUrl": "https://co.com/1"}]
                    ```
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generate(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Dev");
        }

        @Test
        @DisplayName("respects maxResults limit")
        void respectsMaxResults() {
            String html = "<html><body>content</body></html>";
            String aiResponse = """
                    [
                      {"title": "Job1", "companyName": "Co1", "location": "Berlin", "description": "d1", "applyUrl": "https://co.com/1"},
                      {"title": "Job2", "companyName": "Co2", "location": "Berlin", "description": "d2", "applyUrl": "https://co.com/2"},
                      {"title": "Job3", "companyName": "Co3", "location": "Berlin", "description": "d3", "applyUrl": "https://co.com/3"}
                    ]
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generate(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 2, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.jobs()).hasSize(2);
        }

        @Test
        @DisplayName("returns empty when AI response is invalid JSON")
        void emptyOnInvalidJson() {
            String html = "<html><body>content</body></html>";
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generate(anyString(), anyString())).thenReturn("not valid json at all");

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("returns error when WebClient throws")
        void errorOnWebClientException() {
            when(responseSpec.bodyToMono(String.class))
                    .thenReturn(Mono.error(new RuntimeException("Connection refused")));

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("Connection refused");
        }
    }
}
