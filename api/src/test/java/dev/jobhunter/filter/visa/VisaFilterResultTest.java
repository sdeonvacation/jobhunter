package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisaFilterResultTest {

    @Test
    void keep_withVisa() {
        var result = VisaFilterResult.keep(VisaSponsorship.CONFIRMED);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    @Test
    void skip_withReasonAndVisa() {
        var result = VisaFilterResult.skip("visa: no sponsorship", VisaSponsorship.REJECTED);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("visa: no sponsorship");
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.REJECTED);
    }

    @Test
    void bypass_nullVisa() {
        var result = VisaFilterResult.bypass();
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
        assertThat(result.visaSponsorship()).isNull();
    }

    @Test
    void keep_withPending() {
        var result = VisaFilterResult.keep(VisaSponsorship.PENDING);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    @Test
    void keep_withNull() {
        var result = VisaFilterResult.keep(null);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isNull();
    }
}
