package dev.jobhunter.filter;

import com.github.pemistahl.lingua.api.*;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class LanguageFilterImpl implements LanguageFilter {

    private static final double GERMAN_CONFIDENCE_THRESHOLD = 0.85;
    private static final int MIN_TEXT_LENGTH_FOR_DETECTION = 100;

    // Default exclude patterns (German language requirements) — used when config absent
    private static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of(
            "german\\s+c[12]",
            "deutsch\\s+c[12]",
            "flie[ßs]end\\s+deutsch",
            "fluent\\s+german",
            "muttersprache",
            "native\\s+german",
            "german\\s+native",
            "verhandlungssicher"
    );

    // Soft qualifiers that negate a strict requirement (within same sentence)
    private static final Pattern SOFT_QUALIFIER_PATTERN = Pattern.compile(
            "(?i)(nice\\s+to\\s+have|preferred|von\\s+vorteil|" +
                    "\\bB[12]\\b|basic\\s+german|bonus|optional|ideal(ly)?|advantage)",
            Pattern.CASE_INSENSITIVE
    );

    private final Pattern excludePattern;
    private final LanguageDetector languageDetector;

    @Autowired
    public LanguageFilterImpl(PersonalProfileLoader profileLoader) {
        this(profileLoader, buildDefaultDetector());
    }

    // Visible for testing
    LanguageFilterImpl(PersonalProfileLoader profileLoader, LanguageDetector languageDetector) {
        this.languageDetector = languageDetector;

        List<String> patterns = resolveExcludePatterns(profileLoader);
        String regex = patterns.stream()
                .map(p -> "(" + p + ")")
                .collect(Collectors.joining("|"));
        this.excludePattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    @Override
    public FilterResult filter(String jobTitle, String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return FilterResult.keep();
        }

        // Step 1: Check for strict exclude-pattern match (deterministic, fast)
        String combinedText = (jobTitle != null ? jobTitle + " " : "") + jobDescription;
        if (hasStrictExcludeMatch(combinedText)) {
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

    private boolean hasStrictExcludeMatch(String text) {
        var matcher = excludePattern.matcher(text);
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

    private static List<String> resolveExcludePatterns(PersonalProfileLoader profileLoader) {
        if (profileLoader != null) {
            PersonalProfile profile = profileLoader.getProfile();
            if (profile.filters() != null && profile.filters().language() != null) {
                PersonalProfile.LanguageFilterConfig langConfig = profile.filters().language();
                if (langConfig.excludePatterns() != null && !langConfig.excludePatterns().isEmpty()) {
                    return langConfig.excludePatterns();
                }
            }
        }
        return DEFAULT_EXCLUDE_PATTERNS;
    }

    private static LanguageDetector buildDefaultDetector() {
        return LanguageDetectorBuilder.fromLanguages(Language.GERMAN, Language.ENGLISH)
                .withMinimumRelativeDistance(0.15)
                .build();
    }
}
