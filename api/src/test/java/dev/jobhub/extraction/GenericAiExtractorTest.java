package dev.jobhub.extraction;

import dev.jobhub.ai.AiProvider;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.ExtractionStatus;
import org.junit.jupiter.api.BeforeEach;
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
class GenericAiExtractorTest {

    @Mock
    private WebClient webClient;

    @Mock
    private AiProvider aiProvider;

    private TestableGenericAiExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TestableGenericAiExtractor(webClient, aiProvider, 8000);
    }

    @Test
    void supportedTypes_returnsCustom() {
        assertThat(extractor.supportedTypes()).containsExactly(AtsType.CUSTOM);
    }

    @Test
    void canExtract_validEndpoint_returnsTrue() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();
        assertThat(extractor.canExtract(endpoint)).isTrue();
    }

    @Test
    void canExtract_wrongAtsType_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .url("https://example.com/careers")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_nullUrl_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url(null)
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_blankUrl_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("  ")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void extract_aiNotAvailable_returnsError() {
        when(aiProvider.isAvailable()).thenReturn(false);

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.CUSTOM)
                .url("https://example.com/careers")
                .build();

        var result = extractor.extract(endpoint);

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

        var result = extractor.extract(endpoint);

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

        var result = extractor.extract(endpoint);

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

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer");
        assertThat(result.jobs().get(0).location()).isEqualTo("Berlin, Germany");
        assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://example.com/jobs/backend-engineer");
        assertThat(result.jobs().get(1).title()).isEqualTo("Frontend Developer");

        // Verify AI was called with structured pre-extracted content
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

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Senior Java Developer");

        // Verify scripts/styles were removed before sending to AI
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

        var result = extractor.extract(endpoint);
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

        var result = extractor.extract(endpoint);
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

        var result = extractor.extract(endpoint);
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

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Valid Job");
    }

    @Test
    void extract_contentTruncatedToMaxChars() {
        when(aiProvider.isAvailable()).thenReturn(true);

        // Create HTML with very long content
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

        extractor.extract(endpoint);

        // Verify AI received content <= maxContentChars
        verify(aiProvider).extract(anyString(), argThat(content -> content.length() <= 8000), eq(AiExtractionResponse.class));
    }

    @Test
    void generateExternalId_deterministic() {
        String id1 = extractor.generateExternalId("Backend Engineer", "https://example.com/jobs/1");
        String id2 = extractor.generateExternalId("Backend Engineer", "https://example.com/jobs/1");
        String id3 = extractor.generateExternalId("Frontend Dev", "https://example.com/jobs/2");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).hasSize(16); // 8 bytes = 16 hex chars
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

        var result = extractor.extract(endpoint);
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

        var result = extractor.extract(endpoint);

        // Content sent to AI should not contain removed elements
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

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer - Java");
    }

    /**
     * Test-friendly subclass that overrides fetchHtml to avoid network calls.
     */
    private static class TestableGenericAiExtractor extends GenericAiExtractor {
        private String htmlResponse;

        TestableGenericAiExtractor(WebClient webClient, AiProvider aiProvider, int maxContentChars) {
            super(webClient, aiProvider, maxContentChars);
        }

        void setHtmlResponse(String html) {
            this.htmlResponse = html;
        }

        @Override
        String fetchHtml(String url) {
            return htmlResponse;
        }
    }
}
