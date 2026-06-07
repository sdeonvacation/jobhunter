package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;

public record FilterResult(FilterDecision decision, String reason) {

    public static FilterResult keep() {
        return new FilterResult(FilterDecision.KEEP, null);
    }

    public static FilterResult skip(String reason) {
        return new FilterResult(FilterDecision.SKIP, reason);
    }
}
