package dev.jobhunter.people.dto;

import java.util.Map;

public record VisaSuggestionDto(
        Map<String, Boolean> suggestedValues,
        Map<String, String> evidence,
        boolean requiresConfirmation
) {
    public VisaSuggestionDto(Map<String, Boolean> suggestedValues, Map<String, String> evidence) {
        this(suggestedValues, evidence, true);
    }
}
