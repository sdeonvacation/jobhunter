package dev.jobhunter.service;

import java.util.List;
import java.util.Map;

/**
 * Personal profile loaded from profile.yaml.
 */
public record PersonalProfile(
        String name,
        String title,
        int yearsOfExperience,
        List<ProfileSkill> skills,
        Preferences preferences,
        FilterConfig filters,
        ScoringConfig scoring,
        LinkedInSearchConfig linkedInSearch,
        IndeedSearchConfig indeedSearch
) {

    public record ProfileSkill(String name, String proficiency, String category) {
    }

    public record Preferences(
            List<String> locations,
            String employmentType,
            int minSalaryEur,
            List<String> seniority,
            List<String> languages,
            List<String> excludedIndustries
    ) {
    }

    public record FilterConfig(
            RoleFilterConfig role,
            LocationFilterConfig location,
            YoeFilterConfig yoe,
            LanguageFilterConfig language,
            VisaSponsorshipFilterConfig visaSponsorship
    ) {
    }

    public record RoleFilterConfig(
            List<String> includePatterns,
            List<String> excludeKeywords
    ) {
    }

    public record LocationFilterConfig(
            List<String> remotePatterns,
            String unknownAction   // "skip" (default) or "keep"
    ) {
    }

    public record YoeFilterConfig(int maxYears) {
    }

    public record LanguageFilterConfig(
            String target,
            List<String> detectLanguages,
            double confidenceThreshold,
            List<String> excludePatterns,
            List<String> softQualifierPatterns
    ) {
    }

    public record VisaSponsorshipFilterConfig(
            List<String> targetCountries,
            List<String> dePatterns,
            List<String> remoteEuPatterns,
            List<String> positivePatterns,
            List<String> negativePatterns,
            String unknownAction,
            AiFallbackConfig aiFallback
    ) {
    }

    public record AiFallbackConfig(
            boolean enabled,
            int maxDescriptionChars,
            int dailyLimit
    ) {
    }

    public record SeniorityDiscountConfig(
            boolean enabled,
            List<String> keywords,
            double multiplier
    ) {
    }

    public record ScoringConfig(
            double benchmarkWeight,
            ScoringThresholds thresholds,
            List<String> bonusSignals,
            double bonusWeight,
            Map<String, Double> skillWeights,
            Map<String, List<String>> skillVariants,
            List<String> primarySkills,
            int primarySkillCap,
            List<String> competingLanguages,
            int competingLanguageCap,
            SeniorityDiscountConfig seniorityDiscount
    ) {
    }

    public record ScoringThresholds(
            int applyScore,
            int applyMinMatches,
            int maybeScore,
            int maybeMinMatches
    ) {
    }

    public record LinkedInSearchConfig(
            String query,
            List<String> locations,
            String datePosted
    ) {
    }

    public record IndeedSearchConfig(
            List<String> keywords,
            List<String> locations,
            int resultsWanted,
            int hoursOld
    ) {
    }
}
