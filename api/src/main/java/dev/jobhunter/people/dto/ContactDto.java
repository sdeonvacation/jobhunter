package dev.jobhunter.people.dto;

import dev.jobhunter.linkedin.ConnectionStatus;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.model.enums.Seniority;

public record ContactDto(
    String id,
    String personName,
    String title,
    String linkedinUrl,
    String companyId,
    String companyName,
    Seniority seniority,
    ContactDiscoverySource discoveredVia,
    ConnectionStatus connectionStatus,
    int interviewGenerationWeight,
    int warmthScore,
    int contactPriorityScore,
    RelationshipStatus relationshipStatus,
    String lastContactAt,
    String createdAt
) {}
