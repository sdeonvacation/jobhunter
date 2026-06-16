package dev.jobhunter.people.dto;

import java.util.UUID;

public record ContactScore(
    UUID contactId,
    int interviewGenerationWeight,
    int warmthScore,
    int contactPriorityScore
) {}
