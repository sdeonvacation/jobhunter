package dev.jobhunter.filter;

import com.github.pemistahl.lingua.api.*;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class LanguageFilterImpl implements LanguageFilter {

    private static final Logger log = LoggerFactory.getLogger(LanguageFilterImpl.class);
    private static final int MIN_TEXT_LENGTH_FOR_DETECTION = 100;

    private final Pattern excludePattern;
    private final Pattern softQualifierPattern;
    private final LanguageDetector languageDetector;
    private final double confidenceThreshold;

    @Autowired
    public LanguageFilterImpl(PersonalProfileLoader profileLoader) {
        PersonalProfile.LanguageFilterConfig config = resolveConfig(profileLoader);

        this.confidenceThreshold = config != null ? config.confidenceThreshold() : 0.85;
        this.excludePattern = buildExcludePattern(config);
        this.softQualifierPattern = buildSoftQualifierPattern(config);
        this.languageDetector = buildDetector(config != null ? config.detectLanguages() : null);
    }

    // Visible for testing
    LanguageFilterImpl(PersonalProfileLoader profileLoader, LanguageDetector languageDetector) {
        PersonalProfile.LanguageFilterConfig config = resolveConfig(profileLoader);

        this.confidenceThreshold = config != null ? config.confidenceThreshold() : 0.85;
        this.excludePattern = buildExcludePattern(config);
        this.softQualifierPattern = buildSoftQualifierPattern(config);
        this.languageDetector = languageDetector;
    }

    @Override
    public FilterResult filter(String jobTitle, String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return FilterResult.keep();
        }

        // Step 1: Check for strict exclude-pattern match (deterministic, fast)
        if (excludePattern != null) {
            String combinedText = (jobTitle != null ? jobTitle + " " : "") + jobDescription;
            if (hasStrictExcludeMatch(combinedText)) {
                return FilterResult.skip("non-English language required");
            }
        }

        // Step 2: Check if description is primarily non-English (probabilistic)
        if (languageDetector != null && jobDescription.length() >= MIN_TEXT_LENGTH_FOR_DETECTION) {
            // Strip HTML before detection — raw HTML attributes (e.g. class="notion-enable-hover")
            // contain n-gram noise that confuses Lingua into false non-English detections.
            String plainText = Jsoup.parse(jobDescription).body().text();
            String detectedLanguage = detectNonEnglish(plainText.isBlank() ? jobDescription : plainText);
            if (detectedLanguage != null) {
                return FilterResult.skip("non-English JD (" + detectedLanguage + ")");
            }
        }

        return FilterResult.keep();
    }

    /**
     * Detects if text is primarily a non-English language.
     * Returns the language name if confidence exceeds threshold, null otherwise.
     */
    private String detectNonEnglish(String text) {
        try {
            Map<Language, Double> confidenceValues = languageDetector.computeLanguageConfidenceValues(text);

            Language topNonEnglish = null;
            double topConfidence = 0.0;

            for (Map.Entry<Language, Double> entry : confidenceValues.entrySet()) {
                if (entry.getKey() == Language.ENGLISH) {
                    continue;
                }
                if (entry.getValue() > topConfidence) {
                    topConfidence = entry.getValue();
                    topNonEnglish = entry.getKey();
                }
            }

            if (topNonEnglish != null && topConfidence >= confidenceThreshold) {
                // Return capitalized language name: "German", "Dutch", etc.
                String name = topNonEnglish.name();
                return name.charAt(0) + name.substring(1).toLowerCase();
            }
        } catch (Exception e) {
            // If detection fails, default to KEEP (permissive)
            log.debug("Language detection failed, keeping job", e);
        }
        return null;
    }

    private boolean hasStrictExcludeMatch(String text) {
        var matcher = excludePattern.matcher(text);
        while (matcher.find()) {
            // Check if this match is within a "soft" context
            // Look at the surrounding sentence (up to 80 chars before the match)
            int start = Math.max(0, matcher.start() - 80);
            String context = text.substring(start, matcher.end());
            if (softQualifierPattern == null || !softQualifierPattern.matcher(context).find()) {
                return true; // Strict requirement without soft qualifier
            }
        }
        return false;
    }

    private static PersonalProfile.LanguageFilterConfig resolveConfig(PersonalProfileLoader profileLoader) {
        if (profileLoader == null) {
            return null;
        }
        PersonalProfile profile = profileLoader.getProfile();
        if (profile.filters() != null && profile.filters().language() != null) {
            return profile.filters().language();
        }
        return null;
    }

    private static Pattern buildExcludePattern(PersonalProfile.LanguageFilterConfig config) {
        if (config == null || config.excludePatterns() == null || config.excludePatterns().isEmpty()) {
            return null;
        }
        String regex = config.excludePatterns().stream()
                .map(p -> "(" + p + ")")
                .collect(Collectors.joining("|"));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static Pattern buildSoftQualifierPattern(PersonalProfile.LanguageFilterConfig config) {
        if (config == null || config.softQualifierPatterns() == null || config.softQualifierPatterns().isEmpty()) {
            return null;
        }
        String regex = config.softQualifierPatterns().stream()
                .map(p -> "(" + p + ")")
                .collect(Collectors.joining("|"));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static LanguageDetector buildDetector(List<String> languageNames) {
        if (languageNames == null || languageNames.isEmpty()) {
            return null;
        }

        List<Language> languages = new ArrayList<>();
        languages.add(Language.ENGLISH);

        for (String name : languageNames) {
            try {
                Language lang = Language.valueOf(name.toUpperCase());
                if (lang != Language.ENGLISH) {
                    languages.add(lang);
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid language name in detectLanguages config: '{}', skipping", name);
            }
        }

        // Only English resolved — no detection languages configured
        if (languages.size() == 1) {
            return null;
        }

        return LanguageDetectorBuilder.fromLanguages(languages.toArray(new Language[0]))
                .withMinimumRelativeDistance(0.15)
                .build();
    }
}
