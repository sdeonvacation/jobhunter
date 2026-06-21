package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;

/**
 * Result of the visa sponsorship filter step.
 * Wraps a filter decision with the determined visa status (nullable for DE jobs).
 */
public record VisaFilterResult(
        FilterDecision decision,
        String reason,
        VisaSponsorship visaSponsorship
) {

    public static VisaFilterResult keep(VisaSponsorship visa) {
        return new VisaFilterResult(FilterDecision.KEEP, null, visa);
    }

    public static VisaFilterResult skip(String reason, VisaSponsorship visa) {
        return new VisaFilterResult(FilterDecision.SKIP, reason, visa);
    }

    /**
     * Bypass result for German jobs — no visa evaluation needed.
     */
    public static VisaFilterResult bypass() {
        return new VisaFilterResult(FilterDecision.KEEP, null, null);
    }
}
