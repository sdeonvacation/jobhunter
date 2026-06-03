package dev.jobhub.dto;

public record DiscoveryStatsDto(
        long totalDiscovered,
        long totalResolved,
        long activeCompanies,
        long pendingDetection,
        long unsupported
) {}
