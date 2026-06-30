package dev.jobhunter.strategy.direct;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

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

        verify(aiProvider).extract(anyString(), argThat(content -> content.length() <= 8000), eq(AiExtractionResponse.class));
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
        when(aiProvider.isAvailable()).thenReturn(true);
        extractor.setJsonResponse("{\"items\":[{\"id\":\"JOB-1\",\"title\":\"Engineer\",\"city\":[{\"label\":\"Berlin\",\"key\":\"berlin\"}]}]}");
        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of(
                        new AiExtractionResponse.AiJobEntry("Engineer", "Berlin", "https://careers.example.com/JOB-1")
                )));

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
        when(aiProvider.isAvailable()).thenReturn(true);
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
    // firstNonNull — array-of-objects handling
    // -------------------------------------------------------------------------

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
        when(aiProvider.isAvailable()).thenReturn(true);

        extractor.setJsonResponse("""
                {"total":{"value":3},"items":[
                  {"id":"JOB-1","title":"Senior Backend Engineer","city":[{"label":"Berlin","key":"berlin"}],"company":{"label":"Cluster Reply","key":"cluster_reply"}},
                  {"id":"JOB-2","title":"Junior AI Engineer","city":[{"label":"München","key":"munchen"}],"company":{"label":"Axulus Reply","key":"axulus_reply"}},
                  {"id":"JOB-3","title":"DevOps Engineer","city":[{"label":"Hamburg","key":"hamburg"}],"company":{"label":"Reply","key":"reply"}}
                ]}""");

        when(aiProvider.extract(anyString(), anyString(), eq(AiExtractionResponse.class)))
                .thenReturn(new AiExtractionResponse(List.of(
                        new AiExtractionResponse.AiJobEntry("Senior Backend Engineer", "Berlin", "https://www.reply.com/de/about/careers/de/job-details/JOB-1"),
                        new AiExtractionResponse.AiJobEntry("Junior AI Engineer", "München", "https://www.reply.com/de/about/careers/de/job-details/JOB-2"),
                        new AiExtractionResponse.AiJobEntry("DevOps Engineer", "Hamburg", "https://www.reply.com/de/about/careers/de/job-details/JOB-3")
                )));

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
    // Test double
    // -------------------------------------------------------------------------

    /**
     * Overrides both fetchHtml and fetchJson to avoid network calls.
     * Exposes firstNonNull for unit testing and tracks call counts.
     */
    private static class TestableAiPageStrategy extends AiPageStrategy {

        private String htmlResponse;
        private String jsonResponse;
        int htmlFetchCount = 0;
        int jsonFetchCount = 0;

        TestableAiPageStrategy(WebClient webClient, AiProvider aiProvider, int maxContentChars) {
            super(webClient, aiProvider, maxContentChars);
        }

        void setHtmlResponse(String html) { this.htmlResponse = html; }
        void setJsonResponse(String json) { this.jsonResponse = json; }

        @Override
        String fetchHtml(String url) {
            htmlFetchCount++;
            return htmlResponse;
        }

        @Override
        String fetchJson(String url) {
            jsonFetchCount++;
            return jsonResponse;
        }

        /** Expose package-private firstNonNull for direct unit testing. */
        String firstNonNullForTest(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
            return firstNonNull(node, fields);
        }
    }
}
