package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;

public record FilterChainResult(
        FilterDecision decision,
        String reason,
        VisaSponsorship visaSponsorship,
        Integer extractedYoe,
        String countryIso          // ISO2 resolved by LocationFilter; null if unknown
) {
    public static FilterChainResult keep(VisaSponsorship visa, Integer yoe, String countryIso) {
        return new FilterChainResult(FilterDecision.KEEP, null, visa, yoe, countryIso);
    }

    /** Convenience overload when countryIso is not available (e.g. test stubs). */
    public static FilterChainResult keep(VisaSponsorship visa, Integer yoe) {
        return new FilterChainResult(FilterDecision.KEEP, null, visa, yoe, null);
    }

    public static FilterChainResult skip(String reason, VisaSponsorship visa) {
        return new FilterChainResult(FilterDecision.SKIP, reason, visa, null, null);
    }

    public static FilterChainResult skip(String reason) {
        return new FilterChainResult(FilterDecision.SKIP, reason, null, null, null);
    }
}
