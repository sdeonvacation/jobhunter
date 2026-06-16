package dev.jobhunter.people.dto;

import java.util.Map;

public record FunnelDto(
    int applications,
    int recruiterScreen,
    int technical,
    int finalRound,
    int offers,
    Map<String, Double> conversionRates,
    Map<String, Double> avgDaysBetweenStages
) {}
