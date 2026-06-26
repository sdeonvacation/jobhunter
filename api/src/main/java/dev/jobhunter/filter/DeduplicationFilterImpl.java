package dev.jobhunter.filter;

import dev.jobhunter.util.LocationCountryParser;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Generates a deduplication fingerprint for a job posting based on
 * normalized title + company name + country. Using country-level granularity
 * ensures the same job posted across multiple city offices (or by both an
 * aggregator with a city location and an ATS with a country location) produces
 * the same fingerprint and is correctly deduplicated.
 */
@Component
public class DeduplicationFilterImpl implements DeduplicationFilter {

    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "\\b(gmbh|inc|ltd|ag|llc|bv|sa|sas|srl|ug|kg|co|corp|plc|se|nv|as)\\b(\\s*[&+]\\s*\\w+\\.?)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Generate fingerprint from title + company name + country code.
     */
    @Override
    public String generateFingerprint(String title, String companyName, String location) {
        String country = extractCountry(location);
        String normalized = normalize(title) + "|" + normalizeCompany(companyName) + "|" + country;
        return sha256(normalized).substring(0, 16);
    }

    /**
     * Extract country code from a location string for fingerprinting.
     * "Germany" → "de"
     * "Frankfurt am Main, HE, De" → "de"
     * "Hamburg, HH, De" → "de"
     * "Remote" → "remote"
     * Unknown → first normalized segment (fallback)
     */
    String extractCountry(String location) {
        if (location == null || location.isBlank()) return "";

        String country = LocationCountryParser.extractCountry(location);
        if (country != null) return country;

        // Check for remote/global tokens in first segment
        String firstSegment = location.split("[,;]")[0].trim().toLowerCase();
        if (firstSegment.matches("remote|onsite|hybrid|anywhere|global|worldwide")) {
            return "remote";
        }

        // Unknown location: fall back to normalized first segment
        return firstSegment.replaceAll("[^a-z0-9]", "");
    }

    private String normalizeCompany(String input) {
        if (input == null) return "";
        String stripped = LEGAL_SUFFIX.matcher(input).replaceAll("").trim();
        return normalize(stripped);
    }

    private String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
