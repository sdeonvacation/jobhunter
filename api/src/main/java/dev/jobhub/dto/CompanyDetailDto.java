package dev.jobhub.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CompanyDetailDto(
        UUID id,
        String name,
        String domain,
        String country,
        String status,
        String discoveredVia,
        LocalDateTime discoveredAt,
        double priorityScore,
        double avgMatchScore,
        double interviewRate,
        int totalApplications,
        int totalInterviews,
        List<EndpointDto> endpoints,
        int activeJobCount
) {
    public record EndpointDto(
            UUID id,
            String url,
            String atsType,
            boolean isActive,
            String lastCrawlStatus,
            LocalDateTime lastCrawledAt
    ) {}
}
