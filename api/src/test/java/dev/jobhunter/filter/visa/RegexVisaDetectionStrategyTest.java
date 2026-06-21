package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegexVisaDetectionStrategyTest {

    private RegexVisaDetectionStrategy createStrategy(List<String> positivePatterns, List<String> negativePatterns) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(),
                positivePatterns, negativePatterns,
                "skip", null
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));
        return new RegexVisaDetectionStrategy(loader);
    }

    @Test
    void nullDescription_returnsUnknown() {
        var strategy = createStrategy(List.of("visa"), List.of());
        var result = strategy.detect(null);
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("empty description");
    }

    @Test
    void blankDescription_returnsUnknown() {
        var strategy = createStrategy(List.of("visa"), List.of());
        var result = strategy.detect("   ");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("empty description");
    }

    @Test
    void positivePatternMatches_returnsConfirmed() {
        var strategy = createStrategy(List.of("visa\\s+sponsorship"), List.of());
        var result = strategy.detect("We offer visa sponsorship for qualified candidates.");
        assertThat(result.status()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.reason()).contains("visa\\s+sponsorship");
    }

    @Test
    void negativePatternMatches_returnsRejected() {
        var strategy = createStrategy(List.of(), List.of("no visa sponsorship"));
        var result = strategy.detect("Unfortunately, no visa sponsorship is available.");
        assertThat(result.status()).isEqualTo(VisaSponsorship.REJECTED);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.reason()).contains("no visa sponsorship");
    }

    @Test
    void negativeOverridesPositive() {
        var strategy = createStrategy(
                List.of("visa sponsorship"),
                List.of("no visa sponsorship")
        );
        var result = strategy.detect("We provide no visa sponsorship at this time.");
        assertThat(result.status()).isEqualTo(VisaSponsorship.REJECTED);
    }

    @Test
    void noPatternMatches_returnsUnclear() {
        var strategy = createStrategy(List.of("visa sponsorship"), List.of("no visa"));
        var result = strategy.detect("We are looking for a backend engineer with Java experience.");
        assertThat(result.status()).isEqualTo(VisaSponsorship.PENDING);
    }

    @Test
    void caseInsensitiveMatching() {
        var strategy = createStrategy(List.of("visa sponsorship"), List.of());
        var result = strategy.detect("We offer VISA SPONSORSHIP for all candidates.");
        assertThat(result.status()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    @Test
    void noConfig_returnsUnclear() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        var strategy = new RegexVisaDetectionStrategy(loader);
        var result = strategy.detect("Some job description text.");
        assertThat(result.status()).isEqualTo(VisaSponsorship.PENDING);
    }
}
