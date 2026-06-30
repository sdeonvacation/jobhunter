package dev.jobhunter.filter;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts years of experience from job descriptions and filters based on threshold.
 * Skips jobs requiring more than configured max years.
 */
@Component
public class YoeFilterImpl implements YoeFilter {

    private static final int DEFAULT_MAX_YOE = 5;

    private final int maxYoe;

    // Matches patterns like "5+ years of experience", "3 years experience", "7 yrs of software development experience"
    private static final Pattern YOE_PATTERN = Pattern.compile(
            "(\\d+)\\+?\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:[\\w-]+\\s+){0,2}(?:experience|exp)",
            Pattern.CASE_INSENSITIVE
    );

    // Matches patterns like "8+ years as a Backend Engineer", "5+ years in software development"
    private static final Pattern YOE_ROLE_PATTERN = Pattern.compile(
            "(\\d+)\\+?\\s*(?:years?|yrs?)\\s+(?:as|in)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Matches standalone "8+ years" followed by punctuation/conjunction — e.g. "typically 8+ years, but..."
    private static final Pattern YOE_STANDALONE_PATTERN = Pattern.compile(
            "(\\d+)\\+\\s*(?:years?|yrs?)\\s*[,;.()]",
            Pattern.CASE_INSENSITIVE
    );

    public YoeFilterImpl(PersonalProfileLoader profileLoader) {
        PersonalProfile profile = profileLoader.getProfile();
        if (profile.filters() != null && profile.filters().yoe() != null) {
            this.maxYoe = profile.filters().yoe().maxYears();
        } else {
            this.maxYoe = DEFAULT_MAX_YOE;
        }
    }

    /**
     * Extract the required years of experience from description text.
     * Returns null if no YOE requirement found.
     */
    @Override
    public Integer extractYoe(String description) {
        if (description == null || description.isBlank()) return null;

        Integer maxYoeFound = null;

        for (Pattern pattern : new Pattern[]{YOE_PATTERN, YOE_ROLE_PATTERN, YOE_STANDALONE_PATTERN}) {
            Matcher matcher = pattern.matcher(description);
            while (matcher.find()) {
                int yoe = Integer.parseInt(matcher.group(1));
                if (yoe > 20) continue;
                if (maxYoeFound == null || yoe > maxYoeFound) {
                    maxYoeFound = yoe;
                }
            }
        }

        return maxYoeFound;
    }

    /**
     * Filter based on extracted YOE. Returns SKIP if yoe > maxYoe.
     */
    @Override
    public FilterResult filter(Integer yoe) {
        if (yoe == null) return FilterResult.keep();
        if (yoe > maxYoe) {
            return FilterResult.skip("requires " + yoe + "+ years experience");
        }
        return FilterResult.keep();
    }
}
