package dev.jobhub.filter;

import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationFilterImplTest {

    private final LocationFilterImpl filter;

    LocationFilterImplTest() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        filter = new LocationFilterImpl(loader);
    }

    // --- KEEP: Germany ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Germany",
            "Berlin, Germany",
            "Munich",
            "München",
            "Hamburg",
            "Frankfurt am Main",
            "Cologne",
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
            "Nürnberg",
            "Hannover",
            "Bremen",
            "Dortmund"
    })
    void germanLocations_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    // --- SKIP: Netherlands ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Netherlands",
            "Amsterdam",
            "Rotterdam",
            "The Hague",
            "Den Haag",
            "Utrecht",
            "Eindhoven",
            "Delft",
            "Nederland"
    })
    void netherlandsLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
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
    }

    // --- SKIP: Generic/vague locations (whitelist rejects unknowns) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Flexible",
            "Anywhere",
            "EMEA",
            "Europe",
            "EU",
            "Remote, EMEA",
            "2 Locations",
            "3 Locations",
            "5 locations",
            "12 Locations"
    })
    void vagueOrNonWhitelisted_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
    }

    // --- SKIP: null/blank (empty location) ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void nullOrBlank_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: empty");
    }

    // --- SKIP: Unknown location ---

    @Test
    void unknownLocation_skip() {
        var result = filter.filter("Mars Colony");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
    }

    // --- SKIP: US locations ---

    @ParameterizedTest
    @ValueSource(strings = {
            "US, CA, Santa Clara",
            "San Jose",
            "San Francisco, CA",
            "New York, NY",
            "Austin, TX",
            "Seattle, WA",
            "United States",
            "Mountain View",
            "Palo Alto",
            "Sunnyvale",
            "Cupertino",
            "Boston, MA",
            "Denver, CO",
            "Atlanta, GA",
            "Redmond, WA",
            "Bellevue, WA",
            "Chicago, IL",
            "Los Angeles, CA",
            "Portland, OR",
            "Pittsburgh, PA",
            "Raleigh, NC"
    })
    void usLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
    }

    // --- SKIP: India locations ---

    @ParameterizedTest
    @ValueSource(strings = {
            "India, Bengaluru",
            "Bangalore",
            "Hyderabad",
            "Pune",
            "Noida",
            "Mumbai",
            "Gurgaon",
            "Gurugram",
            "Chennai"
    })
    void indiaLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
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
        assertThat(result.reason()).isEqualTo("location: not Germany");
    }

    // --- SKIP: UK locations ---

    @ParameterizedTest
    @ValueSource(strings = {
            "London",
            "Manchester",
            "Edinburgh",
            "Birmingham",
            "Bristol",
            "Cambridge",
            "United Kingdom",
            "UK"
    })
    void ukLocations_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
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
            "Sydney",
            "Seoul",
            "Taipei",
            "São Paulo",
            "Mexico City"
    })
    void otherNonTargetCountries_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
    }

    // --- Edge cases ---

    @Test
    void amsterdamHybrid_skip() {
        var result = filter.filter("Amsterdam, Hybrid");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not Germany");
    }

    @Test
    void remoteEurope_keep() {
        // Matches anchored pattern: ^remote\s*-\s*europe$
        var result = filter.filter("Remote - Europe");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void caseInsensitivity() {
        assertThat(filter.filter("BERLIN").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("AMSTERDAM").decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(filter.filter("REMOTE").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("san francisco").decision()).isEqualTo(FilterDecision.SKIP);
    }
}
