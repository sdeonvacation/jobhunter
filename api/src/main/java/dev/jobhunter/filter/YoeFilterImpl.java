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

        Matcher matcher = YOE_PATTERN.matcher(description);
        Integer minYoe = null;

        while (matcher.find()) {
            int yoe = Integer.parseInt(matcher.group(1));
            // Ignore absurd values (e.g. "25 years ago" false positive)
            if (yoe > 20) continue;
            // Take the first reasonable match (usually the primary requirement)
            if (minYoe == null) {
                minYoe = yoe;
            }
        }

        return minYoe;
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
