package dev.jobhunter.ai;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.jobhunter.strategy.direct.AiExtractionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class OpenAiProviderTest {

    private OpenAiProvider provider;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        provider = new OpenAiProvider(webClient, "test-key",
                wmInfo.getHttpBaseUrl() + "/v1/chat/completions",
                "gpt-4o-mini", "gpt-4o");
    }

    @Test
    void extract_parsesCompleteResponse() {
        String content = """
                {"skills":[{"name":"Java","category":"Language","required":true,"rawMention":"Java 21"}]}""";
        String responseBody = openAiResponse(content, "stop");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        SkillExtractionResponse result = provider.extract(
                "Extract skills", "Job description with Java 21", SkillExtractionResponse.class);

        assertThat(result.skills()).hasSize(1);
        assertThat(result.skills().get(0).name()).isEqualTo("Java");
        assertThat(result.skills().get(0).category()).isEqualTo("Language");
        assertThat(result.skills().get(0).required()).isTrue();
    }

    @Test
    void extract_truncatedResponse_repairsJson() {
        // Simulate truncated JSON: array cut off mid-object, missing closing brackets
        String truncatedContent = """
                {"skills":[{"name":"Java","category":"Language","required":true,"rawMention":"Java"},{"name":"Spring","category":"Framework","required":true,"rawMention":"Spring Boot"},{"name":"Kaf""";
        String responseBody = openAiResponse(truncatedContent, "length");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        SkillExtractionResponse result = provider.extract(
                "Extract skills", "Job description", SkillExtractionResponse.class);

        // Should recover the two complete skills before truncation
        assertThat(result.skills()).hasSize(2);
        assertThat(result.skills().get(0).name()).isEqualTo("Java");
        assertThat(result.skills().get(1).name()).isEqualTo("Spring");
    }

    @Test
    void extract_truncatedResponse_unrepairable_throwsException() {
        // Completely mangled JSON that can't be repaired
        String unreparableContent = """
                {"skills":[{"name":"Ja""";
        String responseBody = openAiResponse(unreparableContent, "length");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        assertThatThrownBy(() -> provider.extract(
                "Extract skills", "Job description", SkillExtractionResponse.class))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("Failed to parse OpenAI extraction response");
    }

    @Test
    void extract_noChoices_throwsException() {
        String responseBody = """
                {"id":"chatcmpl-123","object":"chat.completion","choices":[]}""";

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        assertThatThrownBy(() -> provider.extract(
                "Extract skills", "content", SkillExtractionResponse.class))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("No choices in OpenAI response");
    }

    @Test
    void extract_sendsMaxTokens16384() {
        String content = """
                {"skills":[]}""";
        String responseBody = openAiResponse(content, "stop");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        provider.extract("Extract skills", "content", SkillExtractionResponse.class);

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"max_tokens\":16384")));
    }

    @Test
    void generate_sendsMaxTokens4096() {
        String responseBody = """
                {"id":"chatcmpl-123","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"Generated text"},"finish_reason":"stop"}]}""";

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        String result = provider.generate("System prompt", "User prompt");

        assertThat(result).isEqualTo("Generated text");
        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"max_tokens\":4096")));
    }

    @Test
    void extract_finishReasonStop_completeParse() {
        String content = """
                {"skills":[{"name":"Kotlin","category":"Language","required":false,"rawMention":"Kotlin"}]}""";
        String responseBody = openAiResponse(content, "stop");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        SkillExtractionResponse result = provider.extract(
                "Extract skills", "Uses Kotlin", SkillExtractionResponse.class);

        assertThat(result.skills()).hasSize(1);
        assertThat(result.skills().get(0).name()).isEqualTo("Kotlin");
        assertThat(result.skills().get(0).required()).isFalse();
    }

    @Test
    void extract_truncatedSingleCompleteObject_recoversIt() {
        // One complete object followed by truncated start of second
        String truncatedContent = """
                {"skills":[{"name":"Docker","category":"DevOps","required":true,"rawMention":"Docker"},{"name":"Ku""";
        String responseBody = openAiResponse(truncatedContent, "length");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        SkillExtractionResponse result = provider.extract(
                "Extract skills", "Docker and Kubernetes", SkillExtractionResponse.class);

        assertThat(result.skills()).hasSize(1);
        assertThat(result.skills().get(0).name()).isEqualTo("Docker");
    }

    @Test
    void extract_aiExtractionResponse_sendsCorrectSchema() {
        String content = """
                {"jobs":[{"title":"Backend Engineer","location":"Berlin","applyUrl":"https://example.com/apply"}]}""";
        String responseBody = openAiResponse(content, "stop");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        AiExtractionResponse result = provider.extract(
                "Extract jobs", "<html>jobs page</html>", AiExtractionResponse.class);

        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Backend Engineer");
        assertThat(result.jobs().get(0).location()).isEqualTo("Berlin");
        assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://example.com/apply");

        // Verify schema sent in request
        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"json_schema\""))
                .withRequestBody(containing("\"jobs\""))
                .withRequestBody(containing("\"applyUrl\"")));
    }

    @Test
    void extract_aiExtractionResponse_schemaIncludesRequiredFields() {
        String content = """
                {"jobs":[]}""";
        String responseBody = openAiResponse(content, "stop");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        provider.extract("Extract jobs", "empty page", AiExtractionResponse.class);

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"additionalProperties\":false"))
                .withRequestBody(containing("\"required\":[\"title\",\"location\",\"applyUrl\"]")));
    }

    @Test
    void extract_retriesOnParseFailure_succeedsOnSecondAttempt() {
        // First call returns garbage, second returns valid JSON
        String garbage = "not json at all {{{";
        String validContent = """
                {"skills":[{"name":"Java","category":"Language","required":true,"rawMention":"Java 21"}]}""";

        stubFor(post("/v1/chat/completions")
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(openAiResponse(garbage, "stop")))
                .willSetStateTo("retried"));

        stubFor(post("/v1/chat/completions")
                .inScenario("retry")
                .whenScenarioStateIs("retried")
                .willReturn(okJson(openAiResponse(validContent, "stop"))));

        SkillExtractionResponse result = provider.extract(
                "Extract skills", "Java 21 job", SkillExtractionResponse.class);

        assertThat(result.skills()).hasSize(1);
        assertThat(result.skills().get(0).name()).isEqualTo("Java");

        // Verify two requests were made
        verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void extract_retriesOnParseFailure_throwsAfterBothFail() {
        String garbage = "completely invalid json !!!";
        String responseBody = openAiResponse(garbage, "stop");

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        assertThatThrownBy(() -> provider.extract(
                "Extract skills", "content", SkillExtractionResponse.class))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("Failed to parse");

        // Verify two attempts were made
        verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void extract_nonParseError_doesNotRetry() {
        // "No choices" error should not trigger retry
        String responseBody = """
                {"id":"chatcmpl-123","object":"chat.completion","choices":[]}""";

        stubFor(post("/v1/chat/completions").willReturn(okJson(responseBody)));

        assertThatThrownBy(() -> provider.extract(
                "Extract skills", "content", SkillExtractionResponse.class))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("No choices");

        // Only one request - no retry for non-parse errors
        verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    private String openAiResponse(String content, String finishReason) {
        // Escape the content for embedding in JSON string
        String escapedContent = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return """
                {"id":"chatcmpl-123","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"%s"}]}"""
                .formatted(escapedContent, finishReason);
    }
}
