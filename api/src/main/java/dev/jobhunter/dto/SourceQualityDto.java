package dev.jobhunter.dto;

public record SourceQualityDto(
        String source,
        long totalApplications,
        long totalInterviews,
        double interviewRate
) {}
