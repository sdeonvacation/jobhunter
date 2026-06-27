package dev.jobhunter.dto;

import java.util.List;
import java.util.Map;

public record PatternAnalyticsDto(
        FunnelMetricsDto funnel,
        ScoreComparisonDto scoreComparison,
        List<BlockerEntry> blockerAnalysis,
        List<SkillGapEntry> techStackGaps,
        int scoreThreshold,
        Map<String, Integer> archetypeByCompany,
        Map<String, Integer> archetypeByRemoteType
) {

    public record ScoreComparisonDto(
            double avgScorePositiveOutcome,
            double avgScoreNegativeOutcome,
            int positiveCount,
            int negativeCount
    ) {}

    public record BlockerEntry(
            String reason,
            int count
    ) {}

    public record SkillGapEntry(
            String skill,
            int occurrences
    ) {}
}
