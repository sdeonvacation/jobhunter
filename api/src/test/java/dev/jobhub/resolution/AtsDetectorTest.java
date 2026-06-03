package dev.jobhub.resolution;

import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.Confidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AtsDetectorTest {

    private AtsDetector detector;

    @BeforeEach
    void setUp() {
        detector = new AtsDetector();
    }

    @ParameterizedTest
    @CsvSource({
            "https://stripe.greenhouse.io, GREENHOUSE",
            "https://boards.greenhouse.io/stripe, GREENHOUSE",
            "https://jobs.lever.co/datadog, LEVER",
            "https://jobs.eu.lever.co/personio, LEVER_EU",
            "https://jobs.ashbyhq.com/notion, ASHBY",
            "https://siemens.wd3.myworkdayjobs.com/careers, WORKDAY",
            "https://www.stepstone.de/jobs/backend, STEPSTONE",
    })
    @DisplayName("Should detect ATS from URL with HIGH confidence")
    void shouldDetectFromUrl(String url, AtsType expected) {
        Optional<AtsDetector.DetectionResult> result = detector.detectFromUrl(url);

        assertThat(result).isPresent();
        assertThat(result.get().atsType()).isEqualTo(expected);
        assertThat(result.get().confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("Should return empty for unknown URLs")
    void shouldReturnEmptyForUnknown() {
        assertThat(detector.detectFromUrl("https://www.example.com/careers")).isEmpty();
        assertThat(detector.detectFromUrl("https://careers.google.com")).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for null/blank URL")
    void shouldHandleNullBlank() {
        assertThat(detector.detectFromUrl(null)).isEmpty();
        assertThat(detector.detectFromUrl("")).isEmpty();
        assertThat(detector.detectFromUrl("  ")).isEmpty();
    }

    @Test
    @DisplayName("Should extract slug from URL patterns")
    void shouldExtractSlug() {
        var result = detector.detectFromUrl("https://stripe.greenhouse.io/jobs");
        assertThat(result).isPresent();
        assertThat(result.get().slug()).isEqualTo("stripe");

        result = detector.detectFromUrl("https://jobs.lever.co/datadog");
        assertThat(result).isPresent();
        assertThat(result.get().slug()).isEqualTo("datadog");
    }

    @Test
    @DisplayName("Should detect Greenhouse from HTML content")
    void shouldDetectGreenhouseFromHtml() {
        String html = "<iframe src='https://boards.greenhouse.io/embed/job_board'></iframe>";
        var result = detector.detectFromHtml(html);

        assertThat(result).isPresent();
        assertThat(result.get().atsType()).isEqualTo(AtsType.GREENHOUSE);
        assertThat(result.get().confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("Should detect Lever from HTML content")
    void shouldDetectLeverFromHtml() {
        String html = "<div class='lever-jobs-iframe'>Loading...</div>";
        var result = detector.detectFromHtml(html);

        assertThat(result).isPresent();
        assertThat(result.get().atsType()).isEqualTo(AtsType.LEVER);
        assertThat(result.get().confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("Should return empty for HTML without ATS markers")
    void shouldReturnEmptyForGenericHtml() {
        String html = "<html><body><h1>Careers</h1><p>Apply now</p></body></html>";
        assertThat(detector.detectFromHtml(html)).isEmpty();
    }

    @Test
    @DisplayName("Should handle null/blank HTML")
    void shouldHandleNullBlankHtml() {
        assertThat(detector.detectFromHtml(null)).isEmpty();
        assertThat(detector.detectFromHtml("")).isEmpty();
    }
}
