package dev.jobhunter.filter.geo;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CityCountryResolver.
 * Uses a null/empty profile so the resolver falls back to built-in defaults.
 */
class CityCountryResolverTest {

    private CityCountryResolver resolver;

    @BeforeEach
    void setUp() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        // Return profile with no filter config → resolver falls back to default target set
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "Test", "Engineer", 5, List.of(),
                null, null, null, null, null
        ));
        resolver = new CityCountryResolver(loader);
        resolver.init();
    }

    // -------------------------------------------------------------------------
    // Null / blank input
    // -------------------------------------------------------------------------

    @Test
    void nullInput_returnsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void blankInput_returnsEmpty(String location) {
        assertThat(resolver.resolve(location)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // German cities / country names
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Berlin", "berlin", "BERLIN"})
    void germanCapital_resolvesDe(String location) {
        assertThat(resolver.resolve(location)).contains("DE");
    }

    @Test
    void munchen_resolvesDe() {
        // "münchen" is in GeoNames
        assertThat(resolver.resolve("München")).contains("DE");
    }

    @Test
    void frankfurtAmMain_resolvesDe() {
        assertThat(resolver.resolve("Frankfurt am Main")).contains("DE");
    }

    @Test
    void countryNameGermany_resolvesDe() {
        assertThat(resolver.resolve("Germany")).contains("DE");
    }

    @Test
    void countryNameDeutschland_resolvesDe() {
        assertThat(resolver.resolve("Deutschland")).contains("DE");
    }

    // -------------------------------------------------------------------------
    // Dutch cities / country names
    // -------------------------------------------------------------------------

    @Test
    void amsterdam_resolvesNl() {
        assertThat(resolver.resolve("Amsterdam")).contains("NL");
    }

    @Test
    void rotterdam_resolvesNl() {
        assertThat(resolver.resolve("Rotterdam")).contains("NL");
    }

    @Test
    void veghel_resolvesNl() {
        // Key test: small Dutch city from CSV
        assertThat(resolver.resolve("Veghel")).contains("NL");
    }

    @Test
    void countryNameNetherlands_resolvesNl() {
        assertThat(resolver.resolve("Netherlands")).contains("NL");
    }

    @Test
    void countryNameTheNetherlands_resolvesNl() {
        assertThat(resolver.resolve("The Netherlands")).contains("NL");
    }

    // -------------------------------------------------------------------------
    // Other European target countries
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "Vienna, AT",
        "Wien, AT",
        "Austria, AT",
        "Zurich, CH",
        "Zürich, CH",
        "Switzerland, CH",
        "Dublin, IE",
        "Ireland, IE",
        "Stockholm, SE",
        "Sweden, SE",
        "Copenhagen, DK",
        "Denmark, DK",
        "Helsinki, FI",
        "Finland, FI"
    })
    void targetEuropeanCities_resolveCorrectly(String location, String expectedIso) {
        assertThat(resolver.resolve(location)).contains(expectedIso);
    }

    // -------------------------------------------------------------------------
    // Non-target countries
    // -------------------------------------------------------------------------

    @Test
    void london_resolvesGb() {
        assertThat(resolver.resolve("London")).contains("GB");
    }

    @Test
    void bangalore_resolvesIn() {
        assertThat(resolver.resolve("Bangalore")).contains("IN");
    }

    @Test
    void unitedStates_resolvesUs() {
        assertThat(resolver.resolve("United States")).contains("US");
    }

    @Test
    void paris_resolvesFr() {
        assertThat(resolver.resolve("Paris")).contains("FR");
    }

    // -------------------------------------------------------------------------
    // ISO 2-letter codes (word boundary matched)
    // -------------------------------------------------------------------------

    @Test
    void isoCodeNl_resolvesNl() {
        assertThat(resolver.resolve("NL")).contains("NL");
    }

    @Test
    void isoCodeDe_resolvesDe() {
        assertThat(resolver.resolve("DE")).contains("DE");
    }

    @Test
    void isoCodeLowercase_resolves() {
        assertThat(resolver.resolve("nl")).contains("NL");
    }

    @Test
    void isoCodeInCompound_resolves() {
        // Word boundary: "NL" in compound should match
        assertThat(resolver.resolve("Amsterdam NL")).contains("NL");
    }

    // -------------------------------------------------------------------------
    // Comma-separated location strings
    // -------------------------------------------------------------------------

    @Test
    void commaVeghelNl_resolvesNl() {
        assertThat(resolver.resolve("Veghel, NL")).contains("NL");
    }

    @Test
    void commaMunichGermany_resolvesDe() {
        assertThat(resolver.resolve("Munich, Germany")).contains("DE");
    }

    @Test
    void commaAmsterdamNetherlands_resolvesNl() {
        assertThat(resolver.resolve("Amsterdam, Netherlands")).contains("NL");
    }

    @Test
    void commaBerlinDe_resolvesDe() {
        assertThat(resolver.resolve("Berlin, DE")).contains("DE");
    }

    // -------------------------------------------------------------------------
    // isTargetCountry
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"NL", "DE", "AT", "CH", "IE", "SE", "DK", "FI", "ES"})
    void defaultTargetCountries_areTarget(String iso) {
        assertThat(resolver.isTargetCountry(iso)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GB", "IN", "US", "CN", "CA", "AU", "FR", "IT", "PL"})
    void nonTargetCountries_notTarget(String iso) {
        assertThat(resolver.isTargetCountry(iso)).isFalse();
    }

    @Test
    void nullIso_notTarget() {
        assertThat(resolver.isTargetCountry(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // isVisaExempt
    // -------------------------------------------------------------------------

    @Test
    void de_isVisaExempt() {
        assertThat(resolver.isVisaExempt("DE")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NL", "AT", "CH", "IE", "SE", "GB", "IN", "US"})
    void nonDe_notVisaExempt(String iso) {
        assertThat(resolver.isVisaExempt(iso)).isFalse();
    }

    @Test
    void nullIso_notVisaExempt() {
        assertThat(resolver.isVisaExempt(null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Profile-configured DE patterns
    // -------------------------------------------------------------------------

    @Nested
    class WithDePatterns {

        private CityCountryResolver resolverWithDePatterns;

        @BeforeEach
        void setUp() {
            PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
            var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                    List.of("netherlands", "\\bnl\\b", "amsterdam"),
                    List.of("\\bgermany\\b", "\\bdeutschland\\b", "\\bberlin\\b"),
                    List.of(), List.of(), List.of(), "KEEP", null
            );
            var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
            when(loader.getProfile()).thenReturn(new PersonalProfile(
                    "Test", "Engineer", 5, List.of(),
                    null, filterConfig, null, null, null
            ));
            resolverWithDePatterns = new CityCountryResolver(loader);
            resolverWithDePatterns.init();
        }

        @Test
        void profileDePattern_germany_resolvesDe() {
            assertThat(resolverWithDePatterns.resolve("Germany")).contains("DE");
        }

        @Test
        void profileDePattern_berlin_resolvesDe() {
            assertThat(resolverWithDePatterns.resolve("Berlin")).contains("DE");
        }

        @Test
        void targetCountryPattern_netherlands_resolvesNl() {
            // "netherlands" in targetCountries → NL should be a target
            assertThat(resolverWithDePatterns.isTargetCountry("NL")).isTrue();
        }

        @Test
        void de_alwaysTarget() {
            assertThat(resolverWithDePatterns.isTargetCountry("DE")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // City disambiguation — prefer target country when city exists in multiple
    // -------------------------------------------------------------------------

    @Test
    void amsterdam_prefersNlOverUs() {
        // amsterdam,NL and amsterdam,US both in CSV; NL is target so should be preferred
        Optional<String> result = resolver.resolve("Amsterdam");
        assertThat(result).contains("NL");
    }

    @Test
    void berlin_prefersDe() {
        // berlin,DE and berlin,US in CSV; DE is target
        Optional<String> result = resolver.resolve("Berlin");
        assertThat(result).contains("DE");
    }
}
