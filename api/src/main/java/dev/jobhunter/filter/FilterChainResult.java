package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;

public record FilterChainResult(
        FilterDecision decision,
        String reason,
        VisaSponsorship visaSponsorship,
        Integer extractedYoe
) {
    public static FilterChainResult keep(VisaSponsorship visa, Integer yoe) {
        return new FilterChainResult(FilterDecision.KEEP, null, visa, yoe);
    }

    public static FilterChainResult skip(String reason, VisaSponsorship visa) {
        return new FilterChainResult(FilterDecision.SKIP, reason, visa, null);
    }

    public static FilterChainResult skip(String reason) {
        return new FilterChainResult(FilterDecision.SKIP, reason, null, null);
    }
}
