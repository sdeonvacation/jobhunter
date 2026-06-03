package dev.jobhub.dto;

import java.util.UUID;

public record CompanySummaryDto(
        UUID id,
        String name,
        String domain,
        String country,
        String status,
        double priorityScore,
        int endpointCount,
        double interviewRate,
        int totalApplications
) {}
