package dev.jobhub.dto;

import java.util.Map;
import java.util.UUID;

public record ScoreBreakdownDto(
        UUID jobId,
        int opportunityScore,
        int matchScore,
        String recommendation,
        Map<String, Integer> breakdown
) {}
