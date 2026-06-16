package dev.jobhunter.people.dto;

import java.util.Map;

public record EffectivenessDto(
    Map<String, EffectivenessMetricsDto> byVariant,
    Map<String, EffectivenessMetricsDto> byChannel
) {

    public record EffectivenessMetricsDto(
        int totalSent,
        int replies,
        double replyRate,
        int interviewsGenerated,
        double interviewConversionRate,
        int sampleSize
    ) {}
}
