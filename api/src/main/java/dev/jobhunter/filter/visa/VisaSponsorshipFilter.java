package dev.jobhunter.filter.visa;

public interface VisaSponsorshipFilter {

    /**
     * Evaluate visa sponsorship from job description text only.
     * Location/geo-eligibility is handled by the location filter upstream.
     */
    VisaFilterResult filter(String description, boolean isAggregator);

    /**
     * @deprecated Location parameter is ignored. Delegates to {@link #filter(String, boolean)}.
     * Will be removed once JobFilterChain is updated to use the new signature.
     */
    @Deprecated
    default VisaFilterResult filter(String location, String description, boolean isAggregator) {
        return filter(description, isAggregator);
    }

    /**
     * @deprecated Country extraction is now handled by CityCountryResolver.
     * Throws UnsupportedOperationException — callers must migrate to CityCountryResolver.resolve().
     */
    @Deprecated
    default String extractCountry(String location) {
        throw new UnsupportedOperationException(
                "extractCountry() has been removed. Use CityCountryResolver.resolve() instead.");
    }
}
