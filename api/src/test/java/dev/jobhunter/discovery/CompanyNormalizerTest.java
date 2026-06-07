package dev.jobhunter.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyNormalizerTest {

    private CompanyNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new CompanyNormalizer();
    }

    @ParameterizedTest
    @CsvSource({
            "SAP SE, sap",
            "SAP Deutschland GmbH, sap",
            "Deutsche Bank AG, deutsche bank",
            "Zalando SE, zalando",
            "BMW AG, bmw",
            "Stripe Inc, stripe",
            "Stripe Inc., stripe",
            "Datadog Ltd, datadog",
            "Datadog Ltd., datadog",
            "Microsoft Corp, microsoft",
            "Microsoft Corp., microsoft",
            "HashiCorp LLC, hashicorp",
            "Booking.com BV, booking.com",
            "ASML NV, asml",
            "Airbus SA, airbus",
            "Dassault SAS, dassault",
            "Atlassian Pty, atlassian",
            "Vodafone Limited, vodafone",
    })
    @DisplayName("Should strip legal suffixes correctly")
    void shouldStripLegalSuffixes(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "SAP Deutschland GmbH, sap",
            "SAP Germany GmbH, sap",
            "Siemens Europe AG, siemens",
            "Google International Ltd, google",
            "Meta Global Inc, meta",
    })
    @DisplayName("Should strip country/region qualifiers with legal suffixes")
    void shouldStripCountryQualifiers(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should lowercase and trim")
    void shouldLowercaseAndTrim() {
        assertThat(normalizer.normalize("  NETFLIX  ")).isEqualTo("netflix");
        assertThat(normalizer.normalize("JetBrains")).isEqualTo("jetbrains");
    }

    @Test
    @DisplayName("Should collapse internal whitespace")
    void shouldCollapseWhitespace() {
        assertThat(normalizer.normalize("Deutsche   Bank   AG")).isEqualTo("deutsche bank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("Should return empty string for null/blank input")
    void shouldReturnEmptyForBlank(String input) {
        assertThat(normalizer.normalize(input)).isEqualTo("");
    }

    @Test
    @DisplayName("Should not strip partial matches (conservative)")
    void shouldNotStripPartialMatches() {
        // "Sage" contains "AG" but should not be stripped
        assertThat(normalizer.normalize("Sage")).isEqualTo("sage");
        // "Ginger" should not be affected
        assertThat(normalizer.normalize("Ginger")).isEqualTo("ginger");
        // "Seinc" should not match "Inc"
        assertThat(normalizer.normalize("Seinc")).isEqualTo("seinc");
    }

    @Test
    @DisplayName("Should handle companies with no suffix")
    void shouldHandleNoSuffix() {
        assertThat(normalizer.normalize("Netflix")).isEqualTo("netflix");
        assertThat(normalizer.normalize("Spotify")).isEqualTo("spotify");
        assertThat(normalizer.normalize("Google")).isEqualTo("google");
    }

    @Test
    @DisplayName("Same company different forms should normalize to same value")
    void shouldDeduplicateSameCompany() {
        String sap1 = normalizer.normalize("SAP SE");
        String sap2 = normalizer.normalize("SAP Deutschland GmbH");
        String sap3 = normalizer.normalize("SAP");
        assertThat(sap1).isEqualTo(sap2).isEqualTo(sap3).isEqualTo("sap");
    }
}
