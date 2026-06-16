package dev.jobhunter.people.dto;

import java.util.List;
import java.util.Map;

public record FunnelAnalysisDto(
    String primaryBottleneck,
    String explanation,
    List<String> suggestions,
    Map<String, String> stageInsights
) {}
