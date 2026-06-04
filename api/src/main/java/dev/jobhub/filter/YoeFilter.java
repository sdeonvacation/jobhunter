package dev.jobhub.filter;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts years of experience from job descriptions and filters based on threshold.
 * Skips jobs requiring more than MAX_YOE years.
 */
@Component
public class YoeFilter {

    private static final int MAX_YOE = 5;

    // Matches patterns like "5+ years of experience", "3 years experience", "7 yrs of professional experience"
    private static final Pattern YOE_PATTERN = Pattern.compile(
            "(\\d+)\\+?\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:professional\\s+|relevant\\s+|hands-on\\s+|industry\\s+|work\\s+|software\\s+|engineering\\s+)?(?:experience|exp)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extract the required years of experience from description text.
     * Returns null if no YOE requirement found.
     */
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
     * Filter based on extracted YOE. Returns SKIP if yoe > MAX_YOE.
     */
    public FilterResult filter(Integer yoe) {
        if (yoe == null) return FilterResult.keep();
        if (yoe > MAX_YOE) {
            return FilterResult.skip("requires " + yoe + "+ years experience");
        }
        return FilterResult.keep();
    }
}
