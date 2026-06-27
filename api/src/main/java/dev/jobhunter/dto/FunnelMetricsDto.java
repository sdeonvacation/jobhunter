package dev.jobhunter.dto;

public record FunnelMetricsDto(
        int totalEvaluated,
        int applied,
        int responded,
        int interviewing,
        int offered,
        int rejected,
        double applicationRate,
        double responseRate,
        double interviewRate,
        double offerRate
) {}
