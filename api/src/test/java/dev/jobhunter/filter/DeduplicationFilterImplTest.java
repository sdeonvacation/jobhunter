package dev.jobhunter.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeduplicationFilterImplTest {

    private DeduplicationFilterImpl filter;

    @BeforeEach
    void setUp() {
        filter = new DeduplicationFilterImpl();
    }

    // ── Core regression ──────────────────────────────────────────────────────

    @Test
    void sameJobDifferentCitiesInGermanyProduceSameFingerprint() {
        String fp1 = filter.generateFingerprint("Backend Engineer", "Acme GmbH", "Frankfurt am Main, HE, De");
        String fp2 = filter.generateFingerprint("Backend Engineer", "Acme GmbH", "Hamburg, HH, De");
        String fp3 = filter.generateFingerprint("Backend Engineer", "Acme GmbH", "Germany");

        assertEquals(fp1, fp2, "Frankfurt and Hamburg (same country) must share fingerprint");
        assertEquals(fp2, fp3, "Hamburg and Germany must share fingerprint");
    }

    // ── Cross-country isolation ───────────────────────────────────────────────

    @Test
    void sameJobDifferentCountriesProduceDifferentFingerprints() {
        String fpDe = filter.generateFingerprint("Backend Engineer", "Acme GmbH", "Germany");
        String fpNl = filter.generateFingerprint("Backend Engineer", "Acme GmbH", "Amsterdam, Netherlands");

        assertNotEquals(fpDe, fpNl, "Different countries must produce different fingerprints");
    }

    // ── extractCountry — known German locations ───────────────────────────────

    @Test
    void extractCountryFrankfurtResolvesToDe() {
        assertEquals("de", filter.extractCountry("Frankfurt am Main, HE, De"));
    }

    @Test
    void extractCountryHamburgResolvesToDe() {
        assertEquals("de", filter.extractCountry("Hamburg, HH, De"));
    }

    @Test
    void extractCountryGermanyResolvesToDe() {
        assertEquals("de", filter.extractCountry("Germany"));
    }

    @Test
    void extractCountryBerlinGermanyResolvesToDe() {
        assertEquals("de", filter.extractCountry("Berlin, Germany"));
    }

    // ── extractCountry — remote/global keywords ───────────────────────────────

    @Test
    void extractCountryRemoteResolvesToRemote() {
        assertEquals("remote", filter.extractCountry("Remote"));
    }

    @Test
    void extractCountryWorldwideResolvesToRemote() {
        assertEquals("remote", filter.extractCountry("Worldwide"));
    }

    // ── extractCountry — null / blank ─────────────────────────────────────────

    @Test
    void extractCountryNullReturnsEmptyString() {
        assertEquals("", filter.extractCountry(null));
    }

    @Test
    void extractCountryBlankReturnsEmptyString() {
        assertEquals("", filter.extractCountry("   "));
    }

    @Test
    void extractCountryEmptyStringReturnsEmptyString() {
        assertEquals("", filter.extractCountry(""));
    }

    // ── Fingerprint stability ─────────────────────────────────────────────────

    @Test
    void fingerprintIsStable() {
        String fp1 = filter.generateFingerprint("Software Engineer", "TechCorp", "Germany");
        String fp2 = filter.generateFingerprint("Software Engineer", "TechCorp", "Germany");
        assertEquals(fp1, fp2, "Same inputs must always produce same fingerprint");
    }

    // ── Fingerprint length ────────────────────────────────────────────────────

    @Test
    void fingerprintIs16Chars() {
        String fp = filter.generateFingerprint("Software Engineer", "TechCorp", "Germany");
        assertEquals(16, fp.length(), "Fingerprint must be 16 hex chars");
    }

    @Test
    void fingerprintIs16CharsForNullLocation() {
        String fp = filter.generateFingerprint("Software Engineer", "TechCorp", null);
        assertEquals(16, fp.length());
    }

    // ── Different company → different fingerprint ─────────────────────────────

    @Test
    void differentCompanyProducesDifferentFingerprint() {
        String fp1 = filter.generateFingerprint("Backend Engineer", "CompanyA", "Germany");
        String fp2 = filter.generateFingerprint("Backend Engineer", "CompanyB", "Germany");
        assertNotEquals(fp1, fp2);
    }

    // ── Different title → different fingerprint ───────────────────────────────

    @Test
    void differentTitleProducesDifferentFingerprint() {
        String fp1 = filter.generateFingerprint("Backend Engineer", "Acme GmbH", "Germany");
        String fp2 = filter.generateFingerprint("Frontend Engineer", "Acme GmbH", "Germany");
        assertNotEquals(fp1, fp2);
    }
}
