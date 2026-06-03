package dev.jobhub.resolution;

import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.Confidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMatchResolverTest {

    private PatternMatchResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PatternMatchResolver();
    }

    @ParameterizedTest
    @CsvSource({
            "https://stripe.greenhouse.io/jobs, GREENHOUSE",
            "https://boards.greenhouse.io/stripe, GREENHOUSE",
            "https://boards.greenhouse.io/stripe/jobs/123, GREENHOUSE",
            "https://jobs.lever.co/stripe, LEVER",
            "https://jobs.lever.co/stripe/some-job-id, LEVER",
            "https://jobs.eu.lever.co/personio, LEVER_EU",
            "https://jobs.eu.lever.co/personio/abc-123, LEVER_EU",
            "https://jobs.ashbyhq.com/notion, ASHBY",
            "https://jobs.ashbyhq.com/notion/jobs, ASHBY",
            "https://wd5.myworkdayjobs.com/siemens, WORKDAY",
            "https://siemens.wd3.myworkdayjobs.com/careers, WORKDAY",
    })
    @DisplayName("Should detect ATS from known URL patterns with HIGH confidence")
    void shouldDetectAtsFromUrl(String url, AtsType expectedAts) {
        ResolutionResultDto result = resolver.resolveFromUrl(url);

        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.selectedUrl()).isEqualTo(url);
        assertThat(result.candidateUrls()).hasSize(1);
        assertThat(result.candidateUrls().getFirst().detectedAts()).isEqualTo(expectedAts);
        assertThat(result.candidateUrls().getFirst().discoveredVia()).isEqualTo("PATTERN_MATCH");
    }

    @Test
    @DisplayName("Should return LOW confidence for unknown URLs")
    void shouldReturnLowForUnknownUrls() {
        ResolutionResultDto result = resolver.resolveFromUrl("https://www.example.com/careers");

        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.selectedUrl()).isNull();
        assertThat(result.candidateUrls()).isEmpty();
    }

    @Test
    @DisplayName("Should generate candidate URLs from company name")
    void shouldGenerateCandidateUrls() {
        ResolutionResultDto result = resolver.resolve("stripe", null);

        assertThat(result.candidateUrls()).isNotEmpty();
        assertThat(result.candidateUrls())
                .extracting(ResolutionResultDto.CandidateUrl::url)
                .contains(
                        "https://stripe.greenhouse.io",
                        "https://boards.greenhouse.io/stripe",
                        "https://jobs.lever.co/stripe",
                        "https://jobs.eu.lever.co/stripe",
                        "https://jobs.ashbyhq.com/stripe"
                );
    }

    @Test
    @DisplayName("Should add careers domain candidate when domain provided")
    void shouldAddCareersDomainCandidate() {
        ResolutionResultDto result = resolver.resolve("stripe", "stripe.com");

        assertThat(result.candidateUrls())
                .extracting(ResolutionResultDto.CandidateUrl::url)
                .contains("https://careers.stripe.com");
    }

    @Test
    @DisplayName("Should handle company name with special characters")
    void shouldHandleSpecialCharsInCompanyName() {
        ResolutionResultDto result = resolver.resolve("SAP SE", null);

        // Normalized slug: "sapse" (strips non-alphanumeric)
        assertThat(result.candidateUrls()).isNotEmpty();
        assertThat(result.candidateUrls())
                .extracting(ResolutionResultDto.CandidateUrl::url)
                .contains("https://sapse.greenhouse.io");
    }

    @Test
    @DisplayName("Should not crash on null/empty inputs")
    void shouldHandleNullInputs() {
        ResolutionResultDto result1 = resolver.resolve("", null);
        assertThat(result1.candidateUrls()).isNotEmpty(); // Still generates pattern URLs

        ResolutionResultDto result2 = resolver.resolveFromUrl("");
        assertThat(result2.confidence()).isEqualTo(Confidence.LOW);

        ResolutionResultDto result3 = resolver.resolveFromUrl("not-a-url");
        assertThat(result3.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("Should set needsManualReview=false for pattern match results")
    void shouldNotNeedManualReview() {
        ResolutionResultDto result = resolver.resolveFromUrl("https://jobs.lever.co/datadog");
        assertThat(result.needsManualReview()).isFalse();
    }

    @Test
    @DisplayName("Should set strategyUsed to PATTERN_MATCH")
    void shouldSetCorrectStrategy() {
        ResolutionResultDto result = resolver.resolveFromUrl("https://jobs.ashbyhq.com/notion");
        assertThat(result.strategyUsed()).isEqualTo("PATTERN_MATCH");
    }
}
