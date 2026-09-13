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

import org.mockito.ArgumentCaptor;

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
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

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
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result1 = strategy.fetch(context);

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

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
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

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
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 2, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.jobs()).hasSize(2);
        }

        @Test
        @DisplayName("returns error when AI response is malformed JSON")
        void errorOnMalformedJson() {
            String html = "<html><body>content</body></html>";
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn("not valid json at all");

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
            assertThat(result.errorMessage()).contains("Malformed AI extraction JSON");
        }

        @Test
        @DisplayName("returns empty when AI response is an empty JSON array")
        void emptyOnEmptyJsonArray() {
            String html = "<html><body>content</body></html>";
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn("[]");

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("returns empty when AI response is JSON null")
        void emptyOnJsonNull() {
            String html = "<html><body>content</body></html>";
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn("null");

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

        @Test
        @DisplayName("sends every listing when the page has a large style/script preamble")
        void sendsAllListingsDespiteLargePreamble() {
            int listingCount = 25;
            StringBuilder html = new StringBuilder("<html><head><style>");
            html.append("x".repeat(60_000));
            html.append("</style></head><body>");
            html.append("<div class=\"preamble\">").append("y".repeat(20_000)).append("</div>");
            html.append("<ul class=\"jobs-list-items\">");
            for (int i = 0; i < listingCount; i++) {
                html.append("<li class=\"bjs-jlid\"><div class=\"bjs-jlid__wrapper\"><h4 class=\"bjs-jlid__h\">")
                        .append("<a href=\"https://berlinstartupjobs.com/engineering/job-").append(i).append("/\">Job ")
                        .append(i).append("</a></h4>")
                        .append("<img src=\"https://berlinstartupjobs.com/logo.png\" srcset=\"a 1x, b 2x\" style=\"width:100px\">")
                        .append("</div></li>");
            }
            html.append("</ul></body></html>");

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html.toString()));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn("[]");

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://berlinstartupjobs.com/engineering/"));

            strategy.fetch(context);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(aiProvider).generateExtraction(anyString(), payload.capture());
            String sent = payload.getValue();

            assertThat(sent.length()).isLessThanOrEqualTo(AiAggregatorStrategy.DEFAULT_MAX_HTML_CHARS);
            for (int i = 0; i < listingCount; i++) {
                assertThat(sent).contains("job-" + i + "/");
            }
        }

        @Test
        @DisplayName("narrows the AI payload to the configured contentSelector")
        void narrowsToConfiguredContentSelector() {
            String html = """
                    <html><body>
                      <div class="outside">SHOULD_NOT_BE_SENT <a href="https://x/outside">o</a></div>
                      <ul class="jobs-list-items">
                        <li class="bjs-jlid"><a href="https://berlinstartupjobs.com/engineering/inside/">Inside</a></li>
                      </ul>
                    </body></html>
                    """;
            String aiResponse = """
                    [{"title": "Dev", "companyName": "Co", "location": "Berlin", "description": "Code", "applyUrl": "https://co.com/1"}]
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://berlinstartupjobs.com/engineering/",
                           "contentSelector", "ul.jobs-list-items"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(aiProvider).generateExtraction(anyString(), payload.capture());
            assertThat(payload.getValue()).contains("inside/").doesNotContain("SHOULD_NOT_BE_SENT");
        }

        @Test
        @DisplayName("recovers a JSON array from an AI response with surrounding prose")
        void recoversJsonArrayFromProse() {
            String html = "<html><body>content</body></html>";
            String aiResponse = """
                    Here are the extracted job listings:
                    [{"title": "Dev", "companyName": "Co", "location": "Berlin", "description": "Code", "applyUrl": "https://co.com/1"}]
                    Hope that helps!
                    """;

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(1);
            assertThat(result.jobs().get(0).title()).isEqualTo("Dev");
        }

        @Test
        @DisplayName("recovers complete listings from a truncated AI response")
        void recoversListingsFromTruncatedAiResponse() {
            String html = "<html><body>content</body></html>";
            // Simulates the model hitting its output-token ceiling mid-object.
            String aiResponse = """
                    [
                      {"title": "Job1", "companyName": "Co1", "location": "Berlin", "description": "d1", "applyUrl": "https://co.com/1"},
                      {"title": "Job2", "companyName": "Co2", "location": "Berlin", "description": "d2", "applyUrl": "https://co.com/2"},
                      {"title": "Job3", "companyName": "Co3", "loc""";

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn(aiResponse);

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com"));

            FetchResult result = strategy.fetch(context);

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).hasSize(2);
            assertThat(result.jobs().get(0).title()).isEqualTo("Job1");
            assertThat(result.jobs().get(1).title()).isEqualTo("Job2");
        }

        @Test
        @DisplayName("honours a configured maxHtmlChars limit")
        void honoursConfiguredMaxHtmlChars() {
            String html = "<html><body>" + "<a href=\"https://x/j\">J</a>".repeat(500) + "</body></html>";

            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));
            when(aiProvider.generateExtraction(anyString(), anyString())).thenReturn("[]");

            FetchContext context = FetchContext.forSearch(List.of(), List.of(), 30, 3,
                    Map.of("url", "https://example.com", "maxHtmlChars", 100));

            strategy.fetch(context);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(aiProvider).generateExtraction(anyString(), payload.capture());
            assertThat(payload.getValue().length()).isLessThanOrEqualTo(100);
        }
    }
}
