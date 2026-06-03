package dev.jobhub.filter;

import com.github.pemistahl.lingua.api.*;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class LanguageFilterImpl implements LanguageFilter {

    private static final double GERMAN_CONFIDENCE_THRESHOLD = 0.85;
    private static final int MIN_TEXT_LENGTH_FOR_DETECTION = 100;

    // Strict German requirement patterns
    private static final Pattern STRICT_GERMAN_PATTERN = Pattern.compile(
            "(?i)(german\\s+c[12]|deutsch\\s+c[12]|" +
                    "flie[ßs]end\\s+deutsch|fluent\\s+german|" +
                    "muttersprache|native\\s+german|german\\s+native|" +
                    "verhandlungssicher)",
            Pattern.CASE_INSENSITIVE
    );

    // Soft qualifiers that negate a strict requirement (within same sentence)
    private static final Pattern SOFT_QUALIFIER_PATTERN = Pattern.compile(
            "(?i)(nice\\s+to\\s+have|preferred|von\\s+vorteil|" +
                    "\\bB[12]\\b|basic\\s+german|bonus|optional|ideal(ly)?|advantage)",
            Pattern.CASE_INSENSITIVE
    );

    private final LanguageDetector languageDetector;

    public LanguageFilterImpl() {
        this.languageDetector = LanguageDetectorBuilder.fromLanguages(Language.GERMAN, Language.ENGLISH)
                .withMinimumRelativeDistance(0.15)
                .build();
    }

    // Visible for testing
    LanguageFilterImpl(LanguageDetector languageDetector) {
        this.languageDetector = languageDetector;
    }

    @Override
    public FilterResult filter(String jobTitle, String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return FilterResult.keep();
        }

        // Step 1: Check for strict German requirement patterns (deterministic, fast)
        String combinedText = (jobTitle != null ? jobTitle + " " : "") + jobDescription;
        if (hasStrictGermanRequirement(combinedText)) {
            return FilterResult.skip("German C1/C2 required");
        }

        // Step 2: Check if description is primarily German (probabilistic, for full-German JDs)
        if (jobDescription.length() >= MIN_TEXT_LENGTH_FOR_DETECTION && isPrimarilyGerman(jobDescription)) {
            return FilterResult.skip("German JD");
        }

        return FilterResult.keep();
    }

    private boolean isPrimarilyGerman(String text) {
        try {
            var confidenceValues = languageDetector.computeLanguageConfidenceValues(text);
            Double germanConfidence = confidenceValues.get(Language.GERMAN);
            return germanConfidence != null && germanConfidence >= GERMAN_CONFIDENCE_THRESHOLD;
        } catch (Exception e) {
            // If detection fails, default to KEEP (permissive)
            return false;
        }
    }

    private boolean hasStrictGermanRequirement(String text) {
        var matcher = STRICT_GERMAN_PATTERN.matcher(text);
        while (matcher.find()) {
            // Check if this match is within a "soft" context
            // Look at the surrounding sentence (up to 80 chars before the match)
            int start = Math.max(0, matcher.start() - 80);
            String context = text.substring(start, matcher.end());
            if (!SOFT_QUALIFIER_PATTERN.matcher(context).find()) {
                return true; // Strict requirement without soft qualifier
            }
        }
        return false;
    }
}
