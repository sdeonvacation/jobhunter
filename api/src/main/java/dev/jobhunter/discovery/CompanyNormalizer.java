package dev.jobhunter.discovery;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Normalizes company names for deduplication.
 * Conservative: only exact suffix stripping, no fuzzy matching.
 */
@Component
public class CompanyNormalizer {

    private static final Pattern LEGAL_SUFFIX_PATTERN = Pattern.compile(
            "(?i)\\s+(gmbh|se|ag|inc\\.?|ltd\\.?|corp\\.?|llc|bv|nv|sa|sas|pty|limited|co\\.?)\\s*$"
    );

    private static final Pattern COUNTRY_QUALIFIER_PATTERN = Pattern.compile(
            "(?i)\\s+(deutschland|germany|europe|international|global)\\s*$"
    );

    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s+");

    /**
     * Normalize company name for dedup comparison.
     * "SAP SE" → "sap"
     * "SAP Deutschland GmbH" → "sap"
     * "Deutsche Bank AG" → "deutsche bank"
     */
    public String normalize(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return "";
        }

        String result = companyName.trim();

        // Strip legal suffixes first (may appear after country qualifier)
        result = LEGAL_SUFFIX_PATTERN.matcher(result).replaceAll("");

        // Strip country/region qualifiers that remain after legal suffix removal
        result = COUNTRY_QUALIFIER_PATTERN.matcher(result).replaceAll("");

        // Strip legal suffix again (handles "SAP Deutschland GmbH" → "SAP Deutschland" → "SAP")
        result = LEGAL_SUFFIX_PATTERN.matcher(result).replaceAll("");

        // Collapse whitespace and lowercase
        result = EXTRA_WHITESPACE.matcher(result.trim()).replaceAll(" ");
        return result.toLowerCase().trim();
    }
}
