package dev.jobhunter.people.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.people.service.FunnelAggregator.FunnelData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class FunnelAnalysisTask implements AiTask<FunnelData, FunnelAnalysisTask.FunnelAnalysis> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String systemPrompt(FunnelData input) {
        return """
                You are a job search analytics expert. Analyze the provided job search funnel data \
                and identify the primary bottleneck. Categorize it as one of: \
                "top-of-funnel" (low application-to-screen rate), \
                "interviewing" (dropping between interview stages), or \
                "closing" (failing to convert final rounds to offers).
                
                Provide actionable suggestions. Return ONLY valid JSON with this structure:
                {
                  "primaryBottleneck": "top-of-funnel|interviewing|closing",
                  "explanation": "brief explanation of the bottleneck",
                  "suggestions": ["suggestion1", "suggestion2", "suggestion3"],
                  "stageInsights": {"stage_name": "insight about that stage"}
                }""";
    }

    @Override
    public String userPrompt(FunnelData input) {
        StringBuilder sb = new StringBuilder();
        sb.append("Funnel data:\n");
        sb.append("- Applications: ").append(input.applications()).append("\n");
        sb.append("- Recruiter screens: ").append(input.recruiterScreen()).append("\n");
        sb.append("- Technical interviews: ").append(input.technical()).append("\n");
        sb.append("- Final rounds: ").append(input.finalRound()).append("\n");
        sb.append("- Offers: ").append(input.offers()).append("\n\n");

        sb.append("Conversion rates:\n");
        for (Map.Entry<String, Double> entry : input.conversionRates().entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
                    .append(String.format("%.1f%%", entry.getValue() * 100)).append("\n");
        }

        sb.append("\nAvg days between stages:\n");
        for (Map.Entry<String, Double> entry : input.avgDaysBetweenStages().entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append(" days\n");
        }

        return sb.toString();
    }

    @Override
    public FunnelAnalysis parseResponse(String raw, FunnelData input) {
        try {
            String json = extractJson(raw);
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});

            String primaryBottleneck = (String) parsed.getOrDefault("primaryBottleneck", "unknown");
            String explanation = (String) parsed.getOrDefault("explanation", "");

            @SuppressWarnings("unchecked")
            List<String> suggestions = parsed.get("suggestions") instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();

            @SuppressWarnings("unchecked")
            Map<String, String> stageInsights = parsed.get("stageInsights") instanceof Map<?, ?> map
                    ? map.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toString(),
                            e -> e.getValue().toString()
                    ))
                    : Map.of();

            return new FunnelAnalysis(primaryBottleneck, explanation, suggestions, stageInsights);
        } catch (Exception e) {
            log.warn("Failed to parse funnel analysis response: {}", e.getMessage());
            return new FunnelAnalysis("unknown", "Failed to parse AI response: " + e.getMessage(),
                    List.of(), Map.of());
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    public record FunnelAnalysis(
            String primaryBottleneck,
            String explanation,
            List<String> suggestions,
            Map<String, String> stageInsights
    ) {}
}
