package dev.jobhunter.dto;

import java.util.List;
import java.util.UUID;

public record TailoredResumeDto(
        UUID jobId,
        String jobTitle,
        String companyName,
        String tailoredSummary,
        List<String> highlightedSkills,
        List<String> reorderedExperiencePoints,
        String emphasis
) {}
