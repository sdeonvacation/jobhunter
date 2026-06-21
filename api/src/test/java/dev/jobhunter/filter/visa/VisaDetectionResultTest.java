package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.VisaSponsorship;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisaDetectionResultTest {

    @Test
    void confirmed_setsCorrectFields() {
        var result = VisaDetectionResult.confirmed(0.9, "matched pattern");
        assertThat(result.status()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.reason()).isEqualTo("matched pattern");
    }

    @Test
    void rejected_setsCorrectFields() {
        var result = VisaDetectionResult.rejected(0.85, "negative match");
        assertThat(result.status()).isEqualTo(VisaSponsorship.REJECTED);
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.reason()).isEqualTo("negative match");
    }

    @Test
    void unclear_setsPendingStatus() {
        var result = VisaDetectionResult.unclear();
        assertThat(result.status()).isEqualTo(VisaSponsorship.PENDING);
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.reason()).isEqualTo("no definitive signal");
    }

    @Test
    void unknown_setsUnknownStatus() {
        var result = VisaDetectionResult.unknown("some reason");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.reason()).isEqualTo("some reason");
    }

    @Test
    void isDefinitive_trueForConfirmed() {
        assertThat(VisaDetectionResult.confirmed(0.9, "x").isDefinitive()).isTrue();
    }

    @Test
    void isDefinitive_trueForRejected() {
        assertThat(VisaDetectionResult.rejected(0.9, "x").isDefinitive()).isTrue();
    }

    @Test
    void isDefinitive_falseForPending() {
        assertThat(VisaDetectionResult.unclear().isDefinitive()).isFalse();
    }

    @Test
    void isDefinitive_falseForUnknown() {
        assertThat(VisaDetectionResult.unknown("x").isDefinitive()).isFalse();
    }
}
