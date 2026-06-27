package dev.jobhunter.dto;

import dev.jobhunter.model.enums.EvaluationArchetype;
import dev.jobhunter.model.enums.LegitimacyTier;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record EvaluationDto(
        UUID jobId,
        String jobTitle,
        String companyName,
        Map<String, Object> roleSummary,
        Map<String, Object> cvMatch,
        Map<String, Object> levelStrategy,
        Map<String, Object> compResearch,
        Map<String, Object> customizationPlan,
        Map<String, Object> interviewPlan,
        Map<String, Object> legitimacy,
        int overallScore,
        EvaluationArchetype archetype,
        LegitimacyTier legitimacyTier,
        String descriptionFingerprint,
        LocalDateTime evaluatedAt
) {}
