package dev.jobhunter.filter;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a deduplication fingerprint for a job posting based on
 * normalized title + company name + normalized city. Same job from
 * different sources will produce the same fingerprint.
 */
@Component
public class DeduplicationFilter {

    // ISO country codes and common suffixes to strip
    private static final Pattern COUNTRY_SUFFIX = Pattern.compile(
            ",?\\s*(?:germany|deutschland|de|deu|remote|onsite|hybrid|office)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // State/region names to strip
    private static final Pattern STATE_SUFFIX = Pattern.compile(
            ",?\\s*(?:bayern|bavaria|berlin|hessen|nordrhein-westfalen|nrw|baden-w[uü]rttemberg|sachsen|niedersachsen|hamburg|th[uü]ringen|BE|BY|HE|NW|BW|SN|NI|HH|TH)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Generate fingerprint from title + company name + normalized city.
     */
    public String generateFingerprint(String title, String companyName, String location) {
        String city = extractCity(location);
        String normalized = normalize(title) + "|" + normalize(companyName) + "|" + city;
        return sha256(normalized).substring(0, 16);
    }

    /**
     * Extract the primary city from a location string.
     * "Berlin, Germany" → "berlin"
     * "Berlin, BE, DE" → "berlin"
     * "Berlin, Berlin, Germany" → "berlin"
     * "Munich, Bavaria, Germany" → "munich"
     * "Frankfurt am Main, Hessen" → "frankfurt"
     */
    String extractCity(String location) {
        if (location == null || location.isBlank()) return "";

        // Take first comma-separated segment as candidate city
        String city = location.split("[,;]")[0].trim().toLowerCase();

        // Normalize common city name variants
        city = city.replaceAll("\\s*(am main|an der|a\\.\\s*d\\.).*$", "");

        // Strip "remote", "onsite" etc if they appear as the city
        if (city.matches("remote|onsite|hybrid|anywhere|global|worldwide")) {
            return "remote";
        }

        // Normalize to ASCII-ish
        city = city.replace("ü", "u").replace("ö", "o").replace("ä", "a").replace("ß", "ss");

        return city.replaceAll("[^a-z0-9]", "").trim();
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
