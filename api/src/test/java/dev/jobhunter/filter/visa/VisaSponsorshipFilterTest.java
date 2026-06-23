package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisaSponsorshipFilterTest {

    private static final List<String> DE_PATTERNS = List.of(
            "\\bgermany\\b", "\\bdeutschland\\b", "\\bberlin\\b", "\\bmunich\\b"
    );
    private static final List<String> TARGET_COUNTRIES = List.of("netherlands", "austria", "ireland");
    private static final List<String> REMOTE_EU_PATTERNS = List.of(
            "^remote\\s*-\\s*(eu|europe|netherlands)$"
    );

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
                TARGET_COUNTRIES, DE_PATTERNS, REMOTE_EU_PATTERNS,
                List.of("visa sponsorship"), List.of("no sponsorship"),
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

    // --- German jobs: bypass ---

    @ParameterizedTest
    @ValueSource(strings = {"Berlin, Germany", "Munich", "Deutschland", "germany"})
    void germanLocations_bypass(String location) {
        var filter = createFilter("skip");
        var result = filter.filter(location, "some description", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isNull();
    }

    // --- EU country with confirmed sponsorship ---

    @Test
    void euCountry_confirmed_keep() {
        var filter = createFilter("skip", VisaDetectionResult.confirmed(0.9, "explicit mention"));
        var result = filter.filter("Amsterdam, Netherlands", "We offer visa sponsorship to all candidates.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    // --- EU country with rejected sponsorship ---

    @Test
    void euCountry_rejected_skip() {
        var filter = createFilter("skip", VisaDetectionResult.rejected(0.8, "no sponsorship clause"));
        var result = filter.filter("Vienna, Austria", "We do not sponsor visas.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.REJECTED);
        assertThat(result.reason()).contains("visa:");
    }

    // --- EU country, unknown action = skip ---

    @Test
    void euCountry_unknown_actionSkip_skip() {
        var filter = createFilter("skip", VisaDetectionResult.unknown("no signal"));
        var result = filter.filter("Dublin, Ireland", "Backend engineer position.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    // --- EU country, unknown action = keep ---

    @Test
    void euCountry_unknown_actionKeep_keep() {
        var filter = createFilter("keep", VisaDetectionResult.unknown("no signal"));
        var result = filter.filter("Dublin, Ireland", "Backend engineer position.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    // --- Aggregator mode: EU country with short description → PENDING ---

    @Test
    void aggregator_euCountry_shortDescription_pending() {
        var filter = createFilter("skip");
        var result = filter.filter("Amsterdam, Netherlands", "Short stub", true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    @Test
    void aggregator_euCountry_nullDescription_pending() {
        var filter = createFilter("skip");
        var result = filter.filter("Amsterdam, Netherlands", null, true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    // --- Aggregator mode: EU country with long description → runs detection ---

    @Test
    void aggregator_euCountry_longDescription_runsDetection() {
        String longDesc = "x".repeat(300) + " We offer visa sponsorship.";
        var filter = createFilter("skip", VisaDetectionResult.confirmed(0.85, "explicit"));
        var result = filter.filter("Amsterdam, Netherlands", longDesc, true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    // --- Remote EU pattern match ---

    @Test
    void remoteEu_confirmed_keep() {
        var filter = createFilter("skip", VisaDetectionResult.confirmed(0.9, "match"));
        var result = filter.filter("Remote - EU", "We sponsor visas for all EU offices.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
    }

    @Test
    void remoteEu_aggregator_shortDesc_pending() {
        var filter = createFilter("skip");
        var result = filter.filter("Remote - Netherlands", null, true);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
    }

    // --- Non-target country: bypass ---

    @ParameterizedTest
    @ValueSource(strings = {"San Francisco, US", "London, UK", "Tokyo, Japan", "Mars Colony"})
    void nonTargetCountry_bypass(String location) {
        var filter = createFilter("skip");
        var result = filter.filter(location, "Some description", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isNull();
    }

    // --- Null/blank location: bypass ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void nullOrBlankLocation_bypass(String location) {
        var filter = createFilter("skip");
        var result = filter.filter(location, "Some description", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isNull();
    }

    // --- extractCountry ---

    @Test
    void extractCountry_germany() {
        var filter = createFilter("skip");
        assertThat(filter.extractCountry("Berlin, Germany")).isEqualTo("germany");
        assertThat(filter.extractCountry("Munich")).isEqualTo("germany");
    }

    @Test
    void extractCountry_euTarget() {
        var filter = createFilter("skip");
        assertThat(filter.extractCountry("Amsterdam, Netherlands")).isEqualTo("netherlands");
        assertThat(filter.extractCountry("Vienna, Austria")).isEqualTo("austria");
    }

    @Test
    void extractCountry_nonTarget() {
        var filter = createFilter("skip");
        assertThat(filter.extractCountry("London, UK")).isNull();
        assertThat(filter.extractCountry("San Francisco")).isNull();
    }

    @Test
    void extractCountry_null() {
        var filter = createFilter("skip");
        assertThat(filter.extractCountry(null)).isNull();
        assertThat(filter.extractCountry("")).isNull();
    }

    // --- No config: defaults ---

    @Test
    void noVisaConfig_germanBypass() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        VisaDetectionChain chain = mock(VisaDetectionChain.class);
        var filter = new VisaSponsorshipFilterImpl(chain, loader);

        var result = filter.filter("Berlin, Germany", "desc", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void noVisaConfig_nonGerman_bypass() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        VisaDetectionChain chain = mock(VisaDetectionChain.class);
        var filter = new VisaSponsorshipFilterImpl(chain, loader);

        // With no target countries configured, non-German locations bypass
        var result = filter.filter("Amsterdam, Netherlands", "desc", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isNull();
        verifyNoInteractions(chain);
    }

    // --- LIKELY status keeps ---

    @Test
    void euCountry_likely_keep() {
        var detectionResult = new VisaDetectionResult(VisaSponsorship.LIKELY, 0.6, "implicit signals");
        var filter = createFilter("skip", detectionResult);
        var result = filter.filter("Dublin, Ireland", "International team, relocation support available.", false);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.LIKELY);
    }
}
