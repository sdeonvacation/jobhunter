package dev.jobhunter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a stable SHA-256 hash of a normalized applyUrl for cross-source
 * duplicate detection (L5 dedup layer). Two URLs that differ only in case,
 * query string, or trailing slash collapse to the same hash.
 */
public final class DedupHashUtil {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private DedupHashUtil() {
    }

    public static String compute(String applyUrl) {
        if (applyUrl == null || applyUrl.isBlank()) {
            return null;
        }
        String normalized = applyUrl.toLowerCase();
        int queryIdx = normalized.indexOf('?');
        if (queryIdx >= 0) {
            normalized = normalized.substring(0, queryIdx);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashBytes) {
                hex.append(HEX_DIGITS[(b >> 4) & 0xF]);
                hex.append(HEX_DIGITS[b & 0xF]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
