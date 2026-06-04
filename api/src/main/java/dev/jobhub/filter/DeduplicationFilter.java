package dev.jobhub.filter;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Generates a deduplication fingerprint for a job posting based on
 * normalized title + company name. Same job from different sources
 * will produce the same fingerprint.
 */
@Component
public class DeduplicationFilter {

    /**
     * Generate fingerprint from title + company name + location.
     * Normalizes: lowercase, trim, collapse whitespace, strip punctuation.
     */
    public String generateFingerprint(String title, String companyName, String location) {
        String normalized = normalize(title) + "|" + normalize(companyName) + "|" + normalize(location);
        return sha256(normalized).substring(0, 16);
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
            // Fallback to simple hash
            return Integer.toHexString(input.hashCode());
        }
    }
}
