package dev.jobhunter.people.dto;

public record ScoredActionDto(
    String entityId,
    String type,
    double impactScore,
    double urgencyScore,
    double actionScore,
    String reason,
    String expiresIn,
    String contactId,
    String jobId,
    String contactName,
    String companyName,
    String jobTitle
) {}
