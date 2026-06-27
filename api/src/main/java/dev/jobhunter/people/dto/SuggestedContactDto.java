package dev.jobhunter.people.dto;

import java.util.UUID;

public record SuggestedContactDto(
    UUID id,
    String personName,
    String title,
    String seniority,
    String linkedinUrl,
    String email,
    String emailConfidence,
    int contactPriorityScore
) {}
