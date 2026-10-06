package dev.jobhunter.strategy.direct;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiPageStrategyTest {

    @Mock
    private WebClient webClient;

    @Mock
    private AiProvider aiProvider;

    private TestableAiPageStrategy extractor;

    @BeforeEach
    void setUp() {
        extractor = new TestableAiPageStrategy(webClient, aiProvider, 8000);
    }

    // -------------------------------------------------------------------------
    // Supported types
    // -------------------------------------------------------------------------

    @Test
    void supports_returnsTrue_forCustomType() {
        assertThat(extractor.supportedTypes()).contains(AtsType.CUSTOM);
    }

    @Test
    void supports_returnsFalse_forOtherType() {
        assertThat(extractor.supportedTypes()).doesNotContain(AtsType.GREENHOUSE);
    }

    // -------------------------------------------------------------------------
    // HTML path — basic cases
    // -------------------------------------------------------------------------

    @Test
    void extract_aiNotAvailable_returnsError() {
        when(aiProvider.isAvailable()).thenReturn(false);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("AI provider not available");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_emptyHtml_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_nullHtml_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse(null);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }

    @Test
    void extract_withJobLinks_callsAiWithPreprocessedContent() {
        when(aiProvider.isAvailable()).thenReturn(true);

        String html = """
                <html><body>
                <nav><a href="/">Home</a></nav>
                <main>
                    <div class="jobs">
                        <a href="https://example.com/jobs/backend-engineer">Backend Engineer</a>
                        <span>Berlin, Germany</span>
                    </div>
                    <div class="jobs">
                        <a href="https://example.com/jobs/frontend-dev">Frontend Developer</a>
                        <span>Remote</span>
                    </div>
                </main>
                <footer>Copyright 2024</footer>
                </body></html>
                """;
        extractor.setHtmlResponse(html);

        var aiResponse = new AiExtractionResponse(List.of(
                new AiExtractionResponse.AiJobEntry("Backend Engineer", "Berlin, Germany", "https://example.com/jobs/backend-engineer"),
                new AiExtractionResponse.AiJobEntry("Frontend Developer", "Remote", "https://example.com/jobs/frontend-dev")
        ));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(aiResponse);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer");
        assertThat(result.jobs().get(0).location()).isEqualTo("Berlin, Germany");
        assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://example.com/jobs/backend-engineer");
        assertThat(result.jobs().get(1).title()).isEqualTo("Frontend Developer");

        verify(aiProvider).extract(anyString(), contains("Backend Engineer"), eq(AiExtractionResponse.class));
    }

    @Test
    void extract_noJobLinks_sendsBodyTextToAi() {
        when(aiProvider.isAvailable()).thenReturn(true);

        String html = """
                <html><body>
                <script>var x = 1;</script>
                <style>.foo { color: red; }</style>
                <main>
                    <h1>Join Our Team</h1>
                    <p>We are hiring a Senior Java Developer in Munich.</p>
                    <p>Also looking for a DevOps Engineer, remote.</p>
                </main>
                </body></html>
                """;
        extractor.setHtmlResponse(html);

        var aiResponse = new AiExtractionResponse(List.of(
                new AiExtractionResponse.AiJobEntry("Senior Java Developer", "Munich", null)
        ));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(aiResponse);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Senior Java Developer");

        verify(aiProvider).extract(
                anyString(),
                argThat(content -> !content.contains("var x = 1") && !content.contains("color: red")),
                eq(AiExtractionResponse.class)
        );
    }

    @Test
    void extract_aiReturnsNull_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>Some content</p></main></body></html>");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(null);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }

    @Test
    void extract_aiReturnsEmptyList_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>Some content</p></main></body></html>");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }

    @Test
    void extract_aiThrowsException_returnsError() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>Content</p></main></body></html>");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenThrow(new RuntimeException("AI timeout"));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("RuntimeException");
    }

    @Test
    void extract_requestFailureWithNullMessage_includesUriAndCause() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setFetchException(new WebClientRequestException(
                new IOException("Connection refused"), HttpMethod.GET,
                URI.create("https://n26.example/careers?token=secret"), new HttpHeaders()) {
            @Override
            public String getMessage() {
                return null;
            }
        });

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://n26.example/careers?token=secret")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("WebClientRequestException")
                .contains("URI: https://n26.example/careers?[redacted]")
                .contains("IOException: Connection refused")
                .doesNotContain("WebClientRequestException: null")
                .doesNotContain("secret");
    }

    @Test
    void extract_requestFailureWithNullCauseMessage_includesCauseType() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setFetchException(new WebClientRequestException(
                new UnresolvedAddressException(), HttpMethod.GET,
                URI.create("https://example.com/careers"), new HttpHeaders()) {
            @Override
            public String getMessage() {
                return null;
            }
        });

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("cause: UnresolvedAddressException");
    }

    @Test
    void extract_httpResponseFailure_keepsHttpStatusError() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setFetchException(WebClientResponseException.create(
                503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).isEqualTo("HTTP 503 SERVICE_UNAVAILABLE");
    }

    @Test
    void extract_jobsWithBlankTitles_filteredOut() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>Jobs page</p></main></body></html>");

        var aiResponse = new AiExtractionResponse(List.of(
                new AiExtractionResponse.AiJobEntry("", "Berlin", "url1"),
                new AiExtractionResponse.AiJobEntry(null, "Munich", "url2"),
                new AiExtractionResponse.AiJobEntry("Valid Job", "Berlin", "url3")
        ));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(aiResponse);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Valid Job");
    }

    @Test
    void extract_contentTruncatedToMaxChars() {
        when(aiProvider.isAvailable()).thenReturn(true);

        StringBuilder sb = new StringBuilder("<html><body><main>");
        for (int i = 0; i < 1000; i++) {
            sb.append("<p>This is paragraph number ").append(i).append(" with some filler text to make it long.</p>");
        }
        sb.append("</main></body></html>");
        extractor.setHtmlResponse(sb.toString());

        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        verify(aiProvider, atLeastOnce()).extract(anyString(), argThat(content -> content.length() <= 8000), eq(AiExtractionResponse.class));
    }

    @Test
    void extract_relativeUrls_resolvedCorrectly() {
        when(aiProvider.isAvailable()).thenReturn(true);

        String html = """
                <html><body><main>
                    <a href="/jobs/senior-dev">Senior Developer</a>
                </main></body></html>
                """;
        extractor.setHtmlResponse(html);

        var aiResponse = new AiExtractionResponse(List.of(
                new AiExtractionResponse.AiJobEntry("Senior Developer", null, "/jobs/senior-dev")
        ));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(aiResponse);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://example.com/jobs/senior-dev");
    }

    @Test
    void extract_removesScriptsStylesNavFooter() {
        when(aiProvider.isAvailable()).thenReturn(true);

        String html = """
                <html><body>
                <script>alert('xss')</script>
                <style>body { margin: 0; }</style>
                <nav><a href="/about">About</a></nav>
                <header><h1>Company</h1></header>
                <main>
                    <a href="/jobs/eng">Engineer Position</a>
                </main>
                <footer><p>Footer content</p></footer>
                </body></html>
                """;
        extractor.setHtmlResponse(html);

        var aiResponse = new AiExtractionResponse(List.of(
                new AiExtractionResponse.AiJobEntry("Engineer Position", null, "/jobs/eng")
        ));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(aiResponse);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        verify(aiProvider).extract(anyString(), argThat(content ->
                !content.contains("alert") &&
                        !content.contains("margin") &&
                        !content.contains("About") &&
                        !content.contains("Footer content")
        ), eq(AiExtractionResponse.class));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
    }

    @Test
    void extract_navigationLinksFiltered() {
        when(aiProvider.isAvailable()).thenReturn(true);

        String html = """
                <html><body><main>
                    <a href="/jobs/apply">Apply</a>
                    <a href="/careers">Careers</a>
                    <a href="/jobs/backend-role">Backend Engineer - Java</a>
                </main></body></html>
                """;
        extractor.setHtmlResponse(html);

        var aiResponse = new AiExtractionResponse(List.of(
                new AiExtractionResponse.AiJobEntry("Backend Engineer - Java", null, "/jobs/backend-role")
        ));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(aiResponse);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer - Java");
    }

    // -------------------------------------------------------------------------
    // Chunked extraction
    // -------------------------------------------------------------------------

    @Test
    void extract_moreThanMaxJobsPerChunk_callsAiMultipleTimesAndMerges() {
        when(aiProvider.isAvailable()).thenReturn(true);

        StringBuilder html = new StringBuilder("<html><body><main>");
        for (int i = 0; i < 41; i++) {
            html.append("<a href=\"/jobs/role-").append(i).append("\">Role ").append(i).append("</a>");
        }
        html.append("</main></body></html>");
        extractor.setHtmlResponse(html.toString());

        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(
                        new AiExtractionResponse(List.of(
                                new AiExtractionResponse.AiJobEntry("Role 0", null, "/jobs/role-0")
                        )),
                        new AiExtractionResponse(List.of(
                                new AiExtractionResponse.AiJobEntry("Role 40", null, "/jobs/role-40")
                        ))
                );

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        verify(aiProvider, times(2)).extract(anyString(), anyString(), eq(AiExtractionResponse.class));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).extracting(RawAggregatorJob::title)
                .containsExactly("Role 0", "Role 40");
    }

    @Test
    void extract_shortBody_makesExactlyOneExtractCall() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>Short content</p></main></body></html>");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        verify(aiProvider, times(1)).extract(anyString(), anyString(), eq(AiExtractionResponse.class));
    }

    @Test
    void extract_oneChunkFails_otherSucceeds_returnsSuccessWithPartialJobs() {
        when(aiProvider.isAvailable()).thenReturn(true);

        StringBuilder html = new StringBuilder("<html><body><main>");
        for (int i = 0; i < 41; i++) {
            html.append("<a href=\"/jobs/role-").append(i).append("\">Role ").append(i).append("</a>");
        }
        html.append("</main></body></html>");
        extractor.setHtmlResponse(html.toString());

        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenThrow(new RuntimeException("chunk 1 failed"))
                .thenReturn(new AiExtractionResponse(List.of(
                        new AiExtractionResponse.AiJobEntry("Role 40", null, "/jobs/role-40")
                )));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Role 40");
    }

    // -------------------------------------------------------------------------
    // json_api flag — fetch routing
    // -------------------------------------------------------------------------

    @Test
    void extract_jsonApiFalse_usesFetchHtml() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>content</p></main></body></html>");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(extractor.htmlFetchCount).isEqualTo(1);
        assertThat(extractor.jsonFetchCount).isEqualTo(0);
    }

    @Test
    void extract_jsonApiTrue_usesFetchJson() {
        extractor.setJsonResponse("{\"items\":[{\"id\":\"JOB-1\",\"title\":\"Engineer\",\"city\":[{\"label\":\"Berlin\",\"key\":\"berlin\"}]}]}");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://api.example.com/jobs?country=de")
                .atsSlug("{\"apply_base\":\"https://careers.example.com/\",\"json_api\":true}")
                .build();

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(extractor.jsonFetchCount).isEqualTo(1);
        assertThat(extractor.htmlFetchCount).isEqualTo(0);
    }

    @Test
    void extract_jsonApiTrue_emptyResponse_returnsEmpty() {
        extractor.setJsonResponse(null);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://api.example.com/jobs")
                .atsSlug("{\"json_api\":true}")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }

    // -------------------------------------------------------------------------
    // JSON candidate extraction — extractCandidatesFromJson
    // -------------------------------------------------------------------------

    @Nested
    class ExtractCandidatesFromJson {

        @Test
        void items_wrapper_extractsAllJobs() {
            String json = """
                    {"total":{"value":2},"items":[
                      {"id":"JOB-1","title":"Backend Engineer","city":[{"label":"Berlin","key":"berlin"}]},
                      {"id":"JOB-2","title":"Frontend Developer","city":[{"label":"München","key":"munchen"}]}
                    ]}""";

            var candidates = extractor.extractCandidatesFromJson(json, "https://careers.example.com/");

            assertThat(candidates).hasSize(2);
            assertThat(candidates.get(0).title()).isEqualTo("Backend Engineer");
            assertThat(candidates.get(1).title()).isEqualTo("Frontend Developer");
        }

        @Test
        void data_wrapper_extractsAllJobs() {
            String json = """
                    {"data":[
                      {"title":"Backend Engineer","location":"Berlin","url":"https://example.com/apply/1"}
                    ]}""";

            var candidates = extractor.extractCandidatesFromJson(json, null);

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).title()).isEqualTo("Backend Engineer");
        }

        @Test
        void rootArray_extractsAllJobs() {
            String json = """
                    [{"title":"Engineer","location":"Hamburg","url":"https://example.com/1"}]""";

            var candidates = extractor.extractCandidatesFromJson(json, null);

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).title()).isEqualTo("Engineer");
        }

        @Test
        void city_arrayOfObjects_extractsFirstLabel() {
            String json = """
                    {"items":[{"id":"JOB-1","title":"Engineer",
                      "city":[{"label":"Berlin","key":"berlin"},{"label":"Hamburg","key":"hamburg"}]}
                    ]}""";

            var candidates = extractor.extractCandidatesFromJson(json, null);

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).location()).isEqualTo("Berlin");
        }

        @Test
        void city_scalarString_extractsDirectly() {
            String json = """
                    {"items":[{"id":"JOB-1","title":"Engineer","city":"Berlin"}]}""";

            var candidates = extractor.extractCandidatesFromJson(json, null);

            assertThat(candidates.get(0).location()).isEqualTo("Berlin");
        }

        @Test
        void location_scalarString_extractsDirectly() {
            String json = """
                    {"items":[{"id":"JOB-1","title":"Engineer","location":"Munich"}]}""";

            var candidates = extractor.extractCandidatesFromJson(json, null);

            assertThat(candidates.get(0).location()).isEqualTo("Munich");
        }

        @Test
        void id_field_buildsApplyUrl_withApplyBase() {
            String json = """
                    {"items":[{"id":"JOB-42","title":"Engineer"}]}""";

            var candidates = extractor.extractCandidatesFromJson(json, "https://careers.example.com/job-details/");

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).applyUrl())
                    .isEqualTo("https://careers.example.com/job-details/JOB-42");
        }

        @Test
        void slug_field_buildsApplyUrl_withApplyBase() {
            String json = """
                    {"items":[{"slug":"senior-engineer","title":"Senior Engineer"}]}""";

            var candidates = extractor.extractCandidatesFromJson(json, "https://careers.example.com/jobs/");

            assertThat(candidates.get(0).applyUrl())
                    .isEqualTo("https://careers.example.com/jobs/senior-engineer");
        }

        @Test
        void absoluteUrl_field_usedDirectly() {
            String json = """
                    {"items":[{"title":"Engineer","url":"https://apply.example.com/JOB-1"}]}""";

            var candidates = extractor.extractCandidatesFromJson(json, "https://ignored.com/");

            assertThat(candidates.get(0).applyUrl()).isEqualTo("https://apply.example.com/JOB-1");
        }

        @Test
        void missingTitle_jobSkipped() {
            String json = """
                    {"items":[
                      {"id":"JOB-1","city":[{"label":"Berlin","key":"berlin"}]},
                      {"id":"JOB-2","title":"Valid Engineer","city":[{"label":"Hamburg","key":"hamburg"}]}
                    ]}""";

            var candidates = extractor.extractCandidatesFromJson(json, null);

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).title()).isEqualTo("Valid Engineer");
        }

        @Test
        void malformedJson_returnsEmpty() {
            var candidates = extractor.extractCandidatesFromJson("{not valid json", null);
            assertThat(candidates).isEmpty();
        }

        @Test
        void emptyItemsArray_returnsEmpty() {
            var candidates = extractor.extractCandidatesFromJson("{\"items\":[]}", null);
            assertThat(candidates).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // next_data_positions — deterministic Next.js __NEXT_DATA__ fast-path
    // -------------------------------------------------------------------------

    /** Fixture mirroring the Revolut careers {@code __NEXT_DATA__} payload (positions[]). */
    private static final String NEXT_DATA_HTML = """
            <html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"positions":[{"id":"11111111-2222-3333-4444-555555555555","text":"Test Role","team":"Engineering","locations":[{"name":"Berlin, Germany","type":"office","country":"Germany"}]},{"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","text":"No Loc Role","team":"Data","locations":[]}]}}}</script></body></html>
            """;

    @Test
    void extract_nextDataPositions_skipsAiAndExtractsJobs() {
        extractor.setHtmlResponse(NEXT_DATA_HTML);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"next_data_positions\":true,\"apply_base\":\"https://www.revolut.com/careers/position/\"}")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs()).extracting(RawAggregatorJob::title)
                .containsExactly("Test Role", "No Loc Role");
        assertThat(result.jobs()).extracting(RawAggregatorJob::location)
                .containsExactly("Berlin, Germany", null);
        assertThat(result.jobs().get(0).applyUrl())
                .isEqualTo("https://www.revolut.com/careers/position/11111111-2222-3333-4444-555555555555/");
        assertThat(result.jobs().get(1).applyUrl())
                .isEqualTo("https://www.revolut.com/careers/position/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/");
        // External id mirrors the json_api mapping: generateExternalId(title, applyUrl).
        assertThat(result.jobs().get(0).externalId()).isEqualTo(
                extractor.generateExternalId("Test Role",
                        "https://www.revolut.com/careers/position/11111111-2222-3333-4444-555555555555/"));
        assertThat(extractor.htmlFetchCount).isEqualTo(1);
        verify(aiProvider, never()).isAvailable();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_nextDataPositions_blankContent_returnsEmptyWithoutAi() {
        extractor.setHtmlResponse("");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"next_data_positions\":true}")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        verify(aiProvider, never()).isAvailable();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_nextDataPositions_missingScript_returnsEmptyWithoutAi() {
        extractor.setHtmlResponse("<html><body><p>plain page without next data</p></body></html>");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"next_data_positions\":true,\"apply_base\":\"https://www.revolut.com/careers/position/\"}")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        verify(aiProvider, never()).isAvailable();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Nested
    class ExtractCandidatesFromNextData {

        @Test
        void happyPath_extractsPositionsIncludingMissingLocation() {
            var candidates = extractor.extractCandidatesFromNextData(
                    NEXT_DATA_HTML, "https://www.revolut.com/careers/position/");

            assertThat(candidates).hasSize(2);
            assertThat(candidates.get(0).title()).isEqualTo("Test Role");
            assertThat(candidates.get(0).location()).isEqualTo("Berlin, Germany");
            assertThat(candidates.get(0).applyUrl())
                    .isEqualTo("https://www.revolut.com/careers/position/11111111-2222-3333-4444-555555555555/");
            assertThat(candidates.get(1).title()).isEqualTo("No Loc Role");
            assertThat(candidates.get(1).location()).isNull();
            assertThat(candidates.get(1).applyUrl())
                    .isEqualTo("https://www.revolut.com/careers/position/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/");
        }

        @Test
        void multiLocation_allLocationsJoinedCommaSeparated() {
            // Remote multi-country postings must keep every country: LocationFilter
            // segment-resolves the comma-separated string (ANY target wins) and
            // JobFilterChain's containsVisaExemptCountry scans it for Germany.
            String html = """
                    <html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"positions":[{"id":"22222222-3333-4444-5555-666666666666","text":"AI Role","locations":[{"name":"Austria - Remote","type":"remote","country":"Austria"},{"name":"Germany - Remote","type":"remote","country":"Germany"},{"name":"Austria - Remote","type":"remote","country":"Austria"}]}]}}}</script></body></html>
                    """;

            var candidates = extractor.extractCandidatesFromNextData(
                    html, "https://www.revolut.com/careers/position/");

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).location())
                    .isEqualTo("Austria - Remote, Germany - Remote");
        }

        @Test
        void applyBase_withoutTrailingSlash_isNormalized() {
            var candidates = extractor.extractCandidatesFromNextData(
                    NEXT_DATA_HTML, "https://www.revolut.com/careers/position");

            assertThat(candidates.get(0).applyUrl())
                    .isEqualTo("https://www.revolut.com/careers/position/11111111-2222-3333-4444-555555555555/");
        }

        @Test
        void absentScript_returnsEmpty() {
            assertThat(extractor.extractCandidatesFromNextData(
                    "<html><body><p>plain page</p></body></html>", "https://example.com/")).isEmpty();
        }

        @Test
        void blankOrNullHtml_returnsEmpty() {
            assertThat(extractor.extractCandidatesFromNextData("", "https://example.com/")).isEmpty();
            assertThat(extractor.extractCandidatesFromNextData(null, "https://example.com/")).isEmpty();
        }

        @Test
        void malformedJson_returnsEmpty() {
            String html = """
                    <html><body><script id="__NEXT_DATA__" type="application/json">{not valid json</script></body></html>
                    """;

            assertThat(extractor.extractCandidatesFromNextData(html, "https://example.com/")).isEmpty();
        }

        @Test
        void missingPositions_returnsEmpty() {
            String html = """
                    <html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{}}}</script></body></html>
                    """;

            assertThat(extractor.extractCandidatesFromNextData(html, "https://example.com/")).isEmpty();
        }

        @Test
        void blankIdOrTitle_entriesSkipped() {
            String html = """
                    <html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"positions":[{"id":"","text":"No Id"},{"id":"no-title","text":" "},{"id":"valid-1","text":"Valid Role","locations":[]}]}}}</script></body></html>
                    """;

            var candidates = extractor.extractCandidatesFromNextData(html, "https://example.com/jobs/");

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).title()).isEqualTo("Valid Role");
            assertThat(candidates.get(0).applyUrl()).isEqualTo("https://example.com/jobs/valid-1/");
        }
    }

    // -------------------------------------------------------------------------
    // firstNonNull — array-of-objects handling
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // flaresolverr — Cloudflare-challenged listing fetch
    // -------------------------------------------------------------------------

    /** Response spec of the last flaresolverrStrategy() POST, for request-shape assertions. */
    private WebClient.RequestBodyUriSpec flarePostSpec;
    private WebClient.RequestBodySpec flareBodySpec;

    /** Wraps HTML in a FlareSolverr {@code {"status":"ok","solution":{"response":...}}} body. */
    private static String flareOkJson(String html) throws com.fasterxml.jackson.core.JsonProcessingException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("status", "ok");
        root.putObject("solution").put("response", html);
        return root.toString();
    }

    private TestableAiPageStrategy flaresolverrStrategy(String flareResponseJson) {
        return flaresolverrStrategy(flareResponseJson, null);
    }

    /**
     * Builds a strategy whose WebClient FlareSolverr POST returns
     * {@code flareResponseJson} (or throws {@code postFailure} at request time).
     * Direct fetchHtml/fetchJson still route through the Testable double, so any
     * direct-fetch attempt is counted in htmlFetchCount.
     */
    private TestableAiPageStrategy flaresolverrStrategy(String flareResponseJson, RuntimeException postFailure) {
        WebClient flareClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec postSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        // Raw type: bodyValue() returns RequestHeadersSpec<?> whose capture can't be named.
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        Mono<String> mono = mock(Mono.class);

        when(flareClient.post()).thenReturn(postSpec);
        if (postFailure != null) {
            when(postSpec.uri(anyString())).thenThrow(postFailure);
        } else {
            when(postSpec.uri(anyString())).thenReturn(bodySpec);
            when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            doReturn(headersSpec).when(bodySpec).bodyValue(any());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(String.class)).thenReturn(mono);
            when(mono.block(any(Duration.class))).thenReturn(flareResponseJson);
        }
        flarePostSpec = postSpec;
        flareBodySpec = bodySpec;
        return new TestableAiPageStrategy(flareClient, aiProvider, 8000);
    }

    @Test
    void extract_flaresolverrNextData_fetchesViaProxyAndSkipsAi() throws Exception {
        var strategy = flaresolverrStrategy(flareOkJson(NEXT_DATA_HTML));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"flaresolverr\":true,\"next_data_positions\":true,\"apply_base\":\"https://www.revolut.com/careers/position/\"}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs()).extracting(RawAggregatorJob::title)
                .containsExactly("Test Role", "No Loc Role");
        assertThat(result.jobs().get(0).applyUrl())
                .isEqualTo("https://www.revolut.com/careers/position/11111111-2222-3333-4444-555555555555/");
        // Direct HTML fetch never attempted — content came from FlareSolverr.
        assertThat(strategy.htmlFetchCount).isZero();
        // Request shape: POST {base-url} with cmd/request.get + target url + maxTimeout.
        verify(flarePostSpec).uri("http://localhost:8191/v1");
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(flareBodySpec).bodyValue(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().toString())
                .contains("\"cmd\":\"request.get\"")
                .contains("\"url\":\"https://www.revolut.com/careers/\"")
                .contains("\"maxTimeout\":60000");
        verify(aiProvider, never()).isAvailable();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_flaresolverrErrorStatus_returnsError() {
        var strategy = flaresolverrStrategy(
                "{\"status\":\"error\",\"message\":\"ErrorTimeout: Cloudflare challenge\"}");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"flaresolverr\":true,\"next_data_positions\":true}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage())
                .contains("flaresolverr status=error")
                .contains("ErrorTimeout: Cloudflare challenge");
        assertThat(strategy.htmlFetchCount).isZero();
        verify(aiProvider, never()).isAvailable();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_flaresolverrBlankSolution_returnsError() {
        var strategy = flaresolverrStrategy("{\"status\":\"ok\",\"solution\":{\"response\":\"   \"}}");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"flaresolverr\":true,\"next_data_positions\":true}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("blank solution.response");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_flaresolverrMalformedResponse_returnsError() {
        var strategy = flaresolverrStrategy("this is not json");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"flaresolverr\":true,\"next_data_positions\":true}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("malformed response");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_flaresolverrEmptyBody_returnsError() {
        var strategy = flaresolverrStrategy("");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"flaresolverr\":true,\"next_data_positions\":true}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("empty response");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_flaresolverrTransportFailure_returnsErrorNotSilentEmpty() {
        var strategy = flaresolverrStrategy(null, new WebClientRequestException(
                new IOException("Connection refused"), HttpMethod.POST,
                URI.create("http://localhost:8191/v1"), new HttpHeaders()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://www.revolut.com/careers/")
                .atsSlug("{\"flaresolverr\":true,\"next_data_positions\":true}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("WebClientRequestException").contains("Connection refused");
        assertThat(strategy.htmlFetchCount).isZero();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    void extract_flaresolverrWithoutNextData_fallsThroughToAiPath() throws Exception {
        when(aiProvider.isAvailable()).thenReturn(true);

        String html = """
                <html><body><main>
                    <div class="jobs">
                        <a href="https://example.com/jobs/backend-engineer">Backend Engineer</a>
                        <span>Berlin, Germany</span>
                    </div>
                </main></body></html>
                """;
        var strategy = flaresolverrStrategy(flareOkJson(html));
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of(
                        new AiExtractionResponse.AiJobEntry("Backend Engineer", "Berlin, Germany",
                                "https://example.com/jobs/backend-engineer"))));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .atsSlug("{\"flaresolverr\":true}")
                .build();

        var result = strategy.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer");
        // Content source was FlareSolverr, not the direct HTML fetch.
        assertThat(strategy.htmlFetchCount).isZero();
        verify(aiProvider).extract(anyString(), contains("Backend Engineer"), eq(AiExtractionResponse.class));
    }

    @Nested
    class FirstNonNull {

        @Test
        void scalar_returnsValue() {
            String json = """
                    {"title":"Backend Engineer","location":"Berlin"}""";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "title")).isEqualTo("Backend Engineer");
        }

        @Test
        void array_ofObjects_returnsFirstLabel() {
            String json = """
                    {"city":[{"label":"Berlin","key":"berlin"},{"label":"Hamburg","key":"hamburg"}]}""";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "city")).isEqualTo("Berlin");
        }

        @Test
        void array_ofObjects_noLabel_returnsFirstName() {
            String json = """
                    {"office":[{"name":"HQ","id":"1"}]}""";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "office")).isEqualTo("HQ");
        }

        @Test
        void array_ofScalars_returnsFirstValue() {
            String json = """
                    {"tags":["java","spring"]}""";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "tags")).isEqualTo("java");
        }

        @Test
        void missingField_returnsNull() {
            String json = "{}";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "missing")).isNull();
        }

        @Test
        void nullField_returnsNull() {
            String json = "{\"location\":null}";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "location")).isNull();
        }

        @Test
        void emptyStringField_returnsNull() {
            String json = "{\"title\":\"\"}";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            assertThat(extractor.firstNonNullForTest(node, "title")).isNull();
        }

        @Test
        void fallback_firstNonNullAmongMultipleFields() {
            String json = "{\"id\":\"JOB-99\",\"title\":\"Engineer\"}";
            com.fasterxml.jackson.databind.JsonNode node = parse(json);
            // slug missing, url missing, id present
            assertThat(extractor.firstNonNullForTest(node, "slug", "url", "id")).isEqualTo("JOB-99");
        }

        private com.fasterxml.jackson.databind.JsonNode parse(String json) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // fetchContent routing
    // -------------------------------------------------------------------------

    @Test
    void fetchContent_noPostBody_noJsonApi_callsFetchHtml() {
        extractor.setHtmlResponse("content");
        extractor.fetchContent("https://example.com", null, false);
        assertThat(extractor.htmlFetchCount).isEqualTo(1);
        assertThat(extractor.jsonFetchCount).isEqualTo(0);
    }

    @Test
    void fetchContent_jsonApiTrue_callsFetchJson() {
        extractor.setJsonResponse("{\"items\":[]}");
        extractor.fetchContent("https://api.example.com/jobs", null, true);
        assertThat(extractor.jsonFetchCount).isEqualTo(1);
        assertThat(extractor.htmlFetchCount).isEqualTo(0);
    }

    @Test
    void fetchContent_twoArgOverload_callsFetchHtml() {
        extractor.setHtmlResponse("html");
        extractor.fetchContent("https://example.com", null);
        assertThat(extractor.htmlFetchCount).isEqualTo(1);
        assertThat(extractor.jsonFetchCount).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Full JSON API flow — Reply-style endpoint
    // -------------------------------------------------------------------------

    @Test
    void extract_replyStyleJsonApi_extractsJobsWithCityArray() {
        extractor.setJsonResponse("""
                {"total":{"value":3},"items":[
                  {"id":"JOB-1","title":"Senior Backend Engineer","city":[{"label":"Berlin","key":"berlin"}],"company":{"label":"Cluster Reply","key":"cluster_reply"}},
                  {"id":"JOB-2","title":"Junior AI Engineer","city":[{"label":"München","key":"munchen"}],"company":{"label":"Axulus Reply","key":"axulus_reply"}},
                  {"id":"JOB-3","title":"DevOps Engineer","city":[{"label":"Hamburg","key":"hamburg"}],"company":{"label":"Reply","key":"reply"}}
                ]}""");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://api.reply.com/api/jobpost2?country=de&city=berlin&city=munchen&city=hamburg")
                .atsSlug("{\"apply_base\":\"https://www.reply.com/de/about/careers/de/job-details/\",\"json_api\":true}")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(3);
        assertThat(result.jobs()).extracting(RawAggregatorJob::title)
                .containsExactly("Senior Backend Engineer", "Junior AI Engineer", "DevOps Engineer");
        assertThat(result.jobs()).extracting(RawAggregatorJob::location)
                .containsExactly("Berlin", "München", "Hamburg");
        assertThat(result.jobs().get(0).applyUrl())
                .isEqualTo("https://www.reply.com/de/about/careers/de/job-details/JOB-1");
        assertThat(extractor.jsonFetchCount).isEqualTo(1);
        assertThat(extractor.htmlFetchCount).isEqualTo(0);
    }

    @Test
    void extract_jsonApi_invalidAtsSlug_fallsBackToHtml() {
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setHtmlResponse("<html><body><main><p>Jobs here</p></main></body></html>");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .atsSlug("not-json")       // invalid JSON — should be ignored gracefully
                .build();

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(extractor.htmlFetchCount).isEqualTo(1);
        assertThat(extractor.jsonFetchCount).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // generateExternalId
    // -------------------------------------------------------------------------

    @Test
    void generateExternalId_deterministic() {
        String id1 = extractor.generateExternalId("Backend Engineer", "https://example.com/jobs/1");
        String id2 = extractor.generateExternalId("Backend Engineer", "https://example.com/jobs/1");
        String id3 = extractor.generateExternalId("Frontend Dev", "https://example.com/jobs/2");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).hasSize(16);
    }

    // -------------------------------------------------------------------------
    // link_selector — explicit container scoping for CUSTOM endpoints
    // -------------------------------------------------------------------------

    @Nested
    class LinkSelectorScoping {

        private static final String BASE_URL = "https://berlinstartupjobs.com/engineering/";

        /**
         * Mirrors the berlinstartupjobs.com listing page: the real postings live in
         * {@code li.bjs-jlid h4 a}, while the sidebar holds skill-area and company links.
         * Because the hostname contains "job", the legacy URL-keyword heuristic matches
         * every anchor on this page.
         */
        private static final String BERLIN_STARTUP_JOBS_HTML = """
                <html><body>
                <div class="sidebar">
                    <a href="https://berlinstartupjobs.com/skill-areas/kubernetes/">Kubernetes</a>
                    <a href="https://berlinstartupjobs.com/companies/datatroniq/">DATATRONiQ</a>
                </div>
                <ul class="jobs-list-items">
                    <li class="bjs-jlid"><h4><a href="https://berlinstartupjobs.com/engineering/senior-backend-engineer-golang/">Senior Backend Developer - Go &amp; Kubernetes</a></h4></li>
                    <li class="bjs-jlid"><h4><a href="https://berlinstartupjobs.com/engineering/senior-frontend-engineer-react/">Senior Frontend Developer - React</a></h4></li>
                    <li class="bjs-jlid"><h4><a href="https://berlinstartupjobs.com/engineering/platform-engineer-aws/">Platform Engineer - AWS</a></h4></li>
                </ul>
                </body></html>
                """;

        private static final List<String> REAL_JOB_TITLES = List.of(
                "Senior Backend Developer - Go & Kubernetes",
                "Senior Frontend Developer - React",
                "Platform Engineer - AWS");

        private Document doc() {
            return Jsoup.parse(BERLIN_STARTUP_JOBS_HTML, BASE_URL);
        }

        @Test
        void linkSelector_onAnchor_scopesCandidatesToTheListingContainer() {
            var candidates = extractor.extractCandidateJobs(doc(), BASE_URL, "li.bjs-jlid h4 a");

            assertThat(candidates).extracting(AiPageStrategy.CandidateJob::title)
                    .containsExactlyElementsOf(REAL_JOB_TITLES);
            assertThat(candidates).extracting(AiPageStrategy.CandidateJob::applyUrl)
                    .allMatch(url -> url.startsWith("https://berlinstartupjobs.com/engineering/"));
        }

        @Test
        void linkSelector_onContainer_usesDescendantAnchors() {
            var candidates = extractor.extractCandidateJobs(doc(), BASE_URL, "li.bjs-jlid");

            assertThat(candidates).extracting(AiPageStrategy.CandidateJob::title)
                    .containsExactlyElementsOf(REAL_JOB_TITLES);
        }

        @Test
        void linkSelector_scopedMode_ignoresSidebarSkillAreaAndCompanyLinks() {
            var candidates = extractor.extractCandidateJobs(doc(), BASE_URL, "li.bjs-jlid h4 a");

            assertThat(candidates).extracting(AiPageStrategy.CandidateJob::applyUrl)
                    .noneMatch(url -> url.contains("skill-areas") || url.contains("/companies/"));
        }

        @Test
        void linkSelector_matchingNothing_fallsBackToUnscopedHeuristic() {
            var fallback = extractor.extractCandidateJobs(doc(), BASE_URL, "li.does-not-exist a");
            var unscoped = extractor.extractCandidateJobs(doc(), BASE_URL);

            assertThat(fallback).isEqualTo(unscoped);
            assertThat(fallback).extracting(AiPageStrategy.CandidateJob::title)
                    .contains("Kubernetes", "DATATRONiQ");
        }

        @Test
        void blankLinkSelector_isTreatedAsAbsent() {
            var blank = extractor.extractCandidateJobs(doc(), BASE_URL, "   ");
            var unscoped = extractor.extractCandidateJobs(doc(), BASE_URL);

            assertThat(blank).isEqualTo(unscoped);
        }

        @Test
        void withoutLinkSelector_knownHostContamination_stillTreatsSkillAreaLinkAsCandidate() {
            // Known limitation of the legacy heuristic: JOB_HREF_PATTERN is tested against the
            // absolute href, so "berlinstartupjobs.com" matches "job" for every anchor.
            var candidates = extractor.extractCandidateJobs(doc(), BASE_URL);

            assertThat(candidates).extracting(AiPageStrategy.CandidateJob::applyUrl)
                    .contains("https://berlinstartupjobs.com/skill-areas/kubernetes/",
                            "https://berlinstartupjobs.com/companies/datatroniq/");
            assertThat(candidates).hasSize(5); // 3 real postings + 2 sidebar links
        }

        @Test
        void fetch_linkSelectorFromAtsSlug_sendsOnlyScopedCandidatesToAi() {
            when(aiProvider.isAvailable()).thenReturn(true);
            extractor.setHtmlResponse(BERLIN_STARTUP_JOBS_HTML);
            when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                    .thenReturn(new AiExtractionResponse(List.of(
                            new AiExtractionResponse.AiJobEntry(
                                    "Senior Backend Developer - Go & Kubernetes", null,
                                    "https://berlinstartupjobs.com/engineering/senior-backend-engineer-golang/"))));

            var endpoint = CareerEndpoint.builder()
                    .atsType(AtsType.CUSTOM)
                    .url(BASE_URL)
                    .atsSlug("{\"link_selector\":\"li.bjs-jlid h4 a\"}")
                    .build();

            var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

            assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
            assertThat(result.jobs()).extracting(RawAggregatorJob::title)
                    .containsExactly("Senior Backend Developer - Go & Kubernetes");
            verify(aiProvider).extract(anyString(), argThat(content ->
                    content.contains("/engineering/senior-backend-engineer-golang/")
                            && !content.contains("skill-areas")
                            && !content.contains("/companies/")),
                    eq(AiExtractionResponse.class));
        }

        @Test
        void fetch_linkSelectorCamelCaseAlias_isAccepted() {
            when(aiProvider.isAvailable()).thenReturn(true);
            extractor.setHtmlResponse(BERLIN_STARTUP_JOBS_HTML);
            when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                    .thenReturn(new AiExtractionResponse(List.of()));

            var endpoint = CareerEndpoint.builder()
                    .atsType(AtsType.CUSTOM)
                    .url(BASE_URL)
                    .atsSlug("{\"linkSelector\":\"li.bjs-jlid h4 a\"}")
                    .build();

            extractor.fetch(FetchContext.forEndpoint(endpoint));

            verify(aiProvider).extract(anyString(), argThat(content ->
                    content.contains("/engineering/")
                            && !content.contains("skill-areas")
                            && !content.contains("/companies/")),
                    eq(AiExtractionResponse.class));
        }
    }

    // -------------------------------------------------------------------------
    // Test double
    // -------------------------------------------------------------------------

    /**
     * Overrides both fetchHtml and fetchJson to avoid network calls.
     * Exposes firstNonNull for unit testing and tracks call counts.
     */
    private static class TestableAiPageStrategy extends AiPageStrategy {

        private String htmlResponse;
        private String jsonResponse;
        private RuntimeException fetchException;
        int htmlFetchCount = 0;
        int jsonFetchCount = 0;

        TestableAiPageStrategy(WebClient webClient, AiProvider aiProvider, int maxContentChars) {
            super(webClient, aiProvider, maxContentChars, "http://localhost:8191/v1");
        }

        void setHtmlResponse(String html) { this.htmlResponse = html; }
        void setJsonResponse(String json) { this.jsonResponse = json; }
        void setFetchException(RuntimeException exception) { this.fetchException = exception; }

        @Override
        String fetchHtml(String url) {
            return fetchHtml(url, Map.of());
        }

        @Override
        String fetchHtml(String url, Map<String, String> extraHeaders) {
            htmlFetchCount++;
            if (fetchException != null) throw fetchException;
            return htmlResponse;
        }

        @Override
        String fetchJson(String url) {
            jsonFetchCount++;
            if (fetchException != null) throw fetchException;
            return jsonResponse;
        }

        @Override
        String fetchJson(String url, Map<String, String> extraHeaders) {
            return fetchJson(url);
        }

        /** Expose package-private firstNonNull for direct unit testing. */
        String firstNonNullForTest(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
            return firstNonNull(node, fields);
        }
    }
}
