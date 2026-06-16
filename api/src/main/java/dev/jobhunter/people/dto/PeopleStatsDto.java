package dev.jobhunter.people.dto;

import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.model.enums.Seniority;
import java.util.Map;

public record PeopleStatsDto(
    long totalContacts,
    Map<RelationshipStatus, Long> byStatus,
    Map<Seniority, Long> bySeniority,
    double avgPriorityScore,
    long discoveredToday
) {}
