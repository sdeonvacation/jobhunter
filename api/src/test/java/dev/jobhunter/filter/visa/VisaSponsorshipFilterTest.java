package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisaSponsorshipFilterTest {

    private static final String LONG_DESC = "x".repeat(300) + " We offer visa sponsorship.";

    private VisaSponsorshipFilter createFilter(String unknownAction, VisaDetectionResult detectionResult) {
        PersonalProfileLoader loader = mockLoader(unknownAction);
        VisaDetectionChain chain = mock(VisaDetectionChain.class);
        when(chain.evaluate(anyString())).thenReturn(detectionResult);
        return new VisaSponsorshipFilterImpl(chain, loader);
    }

    private VisaSponsorshipFilter createFilter(String unknownAction) {
        return createFilter(unknownAction, VisaDetectionResult.unknown("no signal"));
    }

    private PersonalProfileLoader mockLoader(String unknownAction) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(),
                List.of(), List.of(),
                unknownAction,
                new PersonalProfile.AiFallbackConfig(false, 4000, 50)
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));
        return loader;
    }

    // --- Aggregator deferral (PENDING) ---

    @Test
    void aggregator_nullDescription_returnsPending() {
        var filter = createFilter("skip");
        var result = filter.filter(null, true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    @Test
    void aggregator_shortDescription_returnsPending() {
        var filter = createFilter("skip");
        var result = filter.filter("Short stub", true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    @Test
    void aggregator_exactlyBoundaryDescription_returnsPending() {
        // 199 chars < 200 threshold → still PENDING
        var filter = createFilter("skip");
        var result = filter.filter("x".repeat(199), true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    @Test
    void aggregator_longDescription_runsDetection() {
        var filter = createFilter("skip", VisaDetectionResult.confirmed(0.9, "explicit"));
        var result = filter.filter(LONG_DESC, true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    // --- Non-aggregator: no deferral, always runs detection ---

    @Test
    void nonAggregator_shortDescription_runsDetection() {
        var filter = createFilter("skip", VisaDetectionResult.rejected(0.8, "no sponsorship"));
        var result = filter.filter("Short", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.REJECTED);
    }

    @Test
    void nonAggregator_nullDescription_runsDetection() {
        var filter = createFilter("skip", VisaDetectionResult.unknown("no signal"));
        var result = filter.filter(null, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    // --- CONFIRMED → KEEP ---

    @Test
    void confirmed_keep() {
        var filter = createFilter("skip", VisaDetectionResult.confirmed(0.9, "explicit mention"));
        var result = filter.filter(LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    // --- LIKELY → KEEP ---

    @Test
    void likely_keep() {
        var detectionResult = new VisaDetectionResult(VisaSponsorship.LIKELY, 0.6, "implicit signals");
        var filter = createFilter("skip", detectionResult);
        var result = filter.filter(LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.LIKELY);
    }

    // --- REJECTED → SKIP ---

    @Test
    void rejected_skip() {
        var filter = createFilter("skip", VisaDetectionResult.rejected(0.8, "no sponsorship clause"));
        var result = filter.filter("We do not sponsor visas for this position.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.REJECTED);
        assertThat(result.reason()).startsWith("visa:");
    }

    // --- UNKNOWN + unknownAction=skip → SKIP ---

    @Test
    void unknown_actionSkip_skip() {
        var filter = createFilter("skip", VisaDetectionResult.unknown("no signal"));
        var result = filter.filter(LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).startsWith("visa:");
    }

    // --- UNKNOWN + unknownAction=keep → KEEP ---

    @Test
    void unknown_actionKeep_keep() {
        var filter = createFilter("keep", VisaDetectionResult.unknown("no signal"));
        var result = filter.filter(LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    // --- No visaConfig in profile: defaults to unknownAction=skip ---

    @Test
    void noVisaConfig_unknownDefaultsToSkip() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        VisaDetectionChain chain = mock(VisaDetectionChain.class);
        when(chain.evaluate(anyString())).thenReturn(VisaDetectionResult.unknown("no signal"));
        var filter = new VisaSponsorshipFilterImpl(chain, loader);

        var result = filter.filter(LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    // --- Deprecated 3-arg signature delegates correctly (backward compat) ---

    @Test
    @SuppressWarnings("deprecation")
    void deprecated3ArgSignature_delegatesToDescriptionOnlyFilter() {
        var filter = createFilter("skip", VisaDetectionResult.confirmed(0.9, "explicit"));
        // location arg must be ignored; result driven purely by description detection
        var result = filter.filter("San Francisco, US", LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecated3ArgSignature_aggregatorDeferral_stillDefers() {
        var filter = createFilter("skip");
        var result = filter.filter("Amsterdam, Netherlands", "Short stub", true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    // --- Deprecated extractCountry throws UnsupportedOperationException ---

    @Test
    @SuppressWarnings("deprecation")
    void extractCountry_throwsUnsupportedOperation() {
        var filter = createFilter("skip");
        assertThatThrownBy(() -> filter.extractCountry("Berlin, Germany"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CityCountryResolver");
        assertThatThrownBy(() -> filter.extractCountry(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- unknownAction case-insensitivity ---

    @ParameterizedTest
    @ValueSource(strings = {"KEEP", "Keep", "kEeP"})
    void unknown_actionKeepCaseInsensitive_keep(String action) {
        var filter = createFilter(action, VisaDetectionResult.unknown("no signal"));
        var result = filter.filter(LONG_DESC, false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }
}
