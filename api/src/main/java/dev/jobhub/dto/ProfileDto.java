package dev.jobhub.dto;

import java.util.List;

public record ProfileDto(
        String name,
        String title,
        int yearsOfExperience,
        List<SkillDto> skills,
        PreferencesDto preferences
) {
    public record SkillDto(
            String name,
            String proficiency,
            String category
    ) {}

    public record PreferencesDto(
            List<String> locations,
            String employmentType,
            int minSalaryEur,
            List<String> seniority,
            List<String> languages,
            List<String> excludedIndustries
    ) {}
}
