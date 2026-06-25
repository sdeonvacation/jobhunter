package dev.jobhunter.filter;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationFilterImplTest {

    /** Creates a CityCountryResolver with default targets (DE/NL/AT/CH/IE/SE/DK/FI/ES). */
    private static CityCountryResolver defaultResolver() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        // Null profile → applyDefaultTargets() → DE/NL/AT/CH/IE/SE/DK/FI/ES
        when(loader.getProfile()).thenReturn(null);
        CityCountryResolver resolver = new CityCountryResolver(loader);
        ReflectionTestUtils.invokeMethod(resolver, "init");
        return resolver;
    }

    private final LocationFilterImpl filter;

    LocationFilterImplTest() {
        PersonalProfileLoader profileLoader = mock(PersonalProfileLoader.class);
        when(profileLoader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        filter = new LocationFilterImpl(profileLoader, defaultResolver());
    }

    // --- KEEP: Germany ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Germany",
            "Berlin, Germany",
            "Munich",
            "Hamburg",
            "Frankfurt am Main",
            "Köln",
            "Stuttgart",
            "Düsseldorf",
            "Walldorf",
            "Heidelberg",
            "Deutschland",
            "Potsdam",
            "Karlsruhe",
            "Mannheim",
            "Bonn",
            "Leipzig",
            "Dresden",
            "Nuremberg",
            "Hannover",
            "Bremen",
            "Dortmund"
    })
    void germanLocations_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("DE");
    }

    // --- KEEP: Netherlands (NL is a default target country) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Netherlands",
            "Amsterdam",
            "Rotterdam",
            "Utrecht",
            "Eindhoven"
    })
    void netherlandsLocations_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("NL");
    }

    // --- KEY TEST: Veghel (small NL city from city CSV) ---

    @Test
    void veghel_keep_nl() {
        var result = filter.filter("Veghel");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("NL");
    }

    // --- KEEP: Other target EU countries ---

    @Test
    void vienna_keep_at() {
        var result = filter.filter("Vienna");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("AT");
    }

    @Test
    void austria_keep_at() {
        var result = filter.filter("Austria");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("AT");
    }

    @Test
    void switzerland_keep_ch() {
        var result = filter.filter("Switzerland");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("CH");
    }

    // --- KEEP: Whitelisted remote patterns ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Remote",
            "remote",
            "Remote - Europe",
            "Remote - EMEA",
            "Remote - EU",
            "Remote - Global",
            "Remote - Worldwide",
            "Remote - DACH",
            "Remote - Germany",
            "Remote-EU"
    })
    void whitelistedRemote_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("REMOTE_EU");
    }

    // --- KEEP: Germany city in compound location ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Hybrid - Berlin",
            "Berlin or Remote",
            "Munich, Germany (Hybrid)"
    })
    void germanyCityInCompound_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("DE");
    }

    // --- KEEP: Amsterdam compound (now resolves to NL) ---

    @Test
    void amsterdamHybrid_keep() {
        var result = filter.filter("Amsterdam, Hybrid");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("NL");
    }

    // --- SKIP: Generic/vague locations (unknown → unknownAction=skip) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Flexible",
            "Anywhere",
            "EMEA",
            "Europe",
            "Remote, EMEA",
            "2 Locations",
            "3 Locations",
            "5 locations",
            "12 Locations"
    })
    void vagueOrUnknown_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not in target locations");
    }

    // --- SKIP: null/blank (empty location) ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void nullOrBlank_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: blank");
    }

    // --- SKIP: Unknown location (unknownAction defaults to "skip") ---

    @Test
    void unknownLocation_skip() {
        var result = filter.filter("Mars Colony");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not in target locations");
    }

    // --- SKIP: US locations ---

    @ParameterizedTest
    @ValueSource(strings = {
            "United States",
            "San Jose",
            "San Francisco, CA",
            "New York",
            "Austin, TX",
            "Seattle, WA",
            "Mountain View",
            "Boston, MA",
            "Chicago, IL",
            "Los Angeles, CA"
    })
    void usLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    // --- SKIP: India locations ---

    @ParameterizedTest
    @ValueSource(strings = {
            "India",
            "Bangalore",
            "Hyderabad",
            "Pune",
            "Mumbai",
            "Chennai"
    })
    void indiaLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    // --- SKIP: Non-whitelisted remote (region not in pattern) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Remote - US",
            "Remote - USA",
            "Remote - India",
            "Remote - APAC",
            "Remote-US",
            "Remote - Canada",
            "Remote - Americas",
            "Remote - Australia"
    })
    void nonWhitelistedRemote_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    // --- SKIP: UK locations ---

    @ParameterizedTest
    @ValueSource(strings = {
            "United Kingdom",
            "England",
            "London",
            "Manchester",
            "Edinburgh",
            "Birmingham"
    })
    void ukLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    // --- SKIP: United Kingdom → reason contains "GB" ---

    @Test
    void unitedKingdom_skip_reasonContainsGB() {
        var result = filter.filter("United Kingdom");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).contains("GB");
    }

    // --- SKIP: Bangalore specifically → reason contains "IN" ---

    @Test
    void bangalore_skip_reasonContainsIN() {
        var result = filter.filter("Bangalore");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).contains("IN");
    }

    // --- SKIP: Other non-target countries ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Israel",
            "Tel Aviv",
            "China",
            "Shanghai",
            "Japan",
            "Tokyo",
            "Singapore",
            "Seoul",
            "São Paulo",
            "Mexico City"
    })
    void otherNonTargetCountries_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    // --- Edge cases ---

    @Test
    void remoteEurope_keep() {
        var result = filter.filter("Remote - Europe");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isEqualTo("REMOTE_EU");
    }

    @Test
    void caseInsensitivity() {
        assertThat(filter.filter("BERLIN").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("AMSTERDAM").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("REMOTE").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("san francisco").decision()).isEqualTo(FilterDecision.SKIP);
    }

    // --- unknownAction=keep ---

    @Nested
    class WithUnknownActionKeep {

        private final LocationFilterImpl filterKeep;

        WithUnknownActionKeep() {
            PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
            when(loader.getProfile()).thenReturn(new PersonalProfile(
                    "", "", 0, List.of(),
                    new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                    new PersonalProfile.FilterConfig(
                            null,
                            new PersonalProfile.LocationFilterConfig(List.of(), "keep"),
                            null, null, null),
                    null, null, null));
            filterKeep = new LocationFilterImpl(loader, defaultResolver());
        }

        @Test
        void unknownCity_keep_nullIso() {
            var result = filterKeep.filter("Mars Colony");
            assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
            assertThat(result.countryIso()).isNull();
        }

        @Test
        void knownTargetCity_stillKeep_withIso() {
            var result = filterKeep.filter("Berlin");
            assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
            assertThat(result.countryIso()).isEqualTo("DE");
        }

        @Test
        void nonTargetCountry_stillSkip() {
            // London resolves via city CSV (may return CA, GB, or US — all non-target)
            var result = filterKeep.filter("United Kingdom");
            assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        }

        @Test
        void remoteEu_keep_remoteEuIso() {
            var result = filterKeep.filter("Remote - EU");
            assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
            assertThat(result.countryIso()).isEqualTo("REMOTE_EU");
        }
    }
}
