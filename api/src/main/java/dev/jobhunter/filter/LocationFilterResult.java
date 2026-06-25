package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;

/**
 * Result from the location filter. Carries the resolved ISO2 country code
 * so downstream (JobFilterChain) can decide whether visa check is needed.
 */
public record LocationFilterResult(
        FilterDecision decision,
        String reason,
        String countryIso   // "DE", "NL", "AT", etc.; REMOTE_EU for remote matches; null if unresolved
) {
    /** Pseudo-ISO used when location matches a remote-EU pattern. Not a real country code. */
    public static final String REMOTE_EU = "REMOTE_EU";

    public static LocationFilterResult keep(String countryIso) {
        return new LocationFilterResult(FilterDecision.KEEP, null, countryIso);
    }

    public static LocationFilterResult skip(String reason) {
        return new LocationFilterResult(FilterDecision.SKIP, reason, null);
    }
}
