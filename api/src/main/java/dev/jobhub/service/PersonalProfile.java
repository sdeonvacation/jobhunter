package dev.jobhub.service;

import java.util.List;

/**
 * Personal profile loaded from profile.yaml.
 */
public record PersonalProfile(
        String name,
        String title,
        int yearsOfExperience,
        List<ProfileSkill> skills,
        Preferences preferences
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
}
