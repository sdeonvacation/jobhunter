package dev.jobhub.filter;

import dev.jobhub.model.enums.FilterDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LocationFilterImplTest {

    private final LocationFilterImpl filter = new LocationFilterImpl();

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
    }

    // --- KEEP: Generic remote / flexible ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Remote",
            "remote",
            "Hybrid",
            "Flexible",
            "Anywhere",
            "Remote - Europe",
            "Remote - EMEA",
            "Hybrid - Berlin"
    })
    void genericRemote_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    // --- KEEP: EMEA / Europe ---

    @ParameterizedTest
    @ValueSource(strings = {
            "EMEA",
            "Europe",
            "EU",
            "Remote, EMEA"
    })
    void emeaEurope_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    // --- KEEP: Multi-location ---

    @ParameterizedTest
    @ValueSource(strings = {
            "2 Locations",
            "3 Locations",
            "5 locations",
            "12 Locations"
    })
    void multiLocation_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    // --- KEEP: null/blank ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void nullOrBlank_keep(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    // --- KEEP: Unknown location (permissive default) ---

    @Test
    void unknownLocation_keep() {
        var result = filter.filter("Mars Colony");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
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
        assertThat(result.reason()).isEqualTo("location: US");
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
        assertThat(result.reason()).isEqualTo("location: India");
    }

    // --- SKIP: Restricted remote ---

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
    void restrictedRemote_skip(String location) {
        var result = filter.filter(location);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: restricted remote");
    }

    // --- SKIP: UK-only ---

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
        assertThat(result.reason()).isEqualTo("location: UK");
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
        assertThat(result.reason()).isEqualTo("location: non-target country");
    }

    // --- Edge cases: target location takes precedence ---

    @Test
    void berlinWithRemote_keep() {
        // Germany pattern matches first
        var result = filter.filter("Berlin or Remote");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void amsterdamHybrid_skip() {
        var result = filter.filter("Amsterdam, Hybrid");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void remoteEurope_keep() {
        // "Remote - Europe" contains "Remote" and the restricted pattern won't match "Europe"
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
