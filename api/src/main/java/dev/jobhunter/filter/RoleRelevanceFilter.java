package dev.jobhunter.filter;

public interface RoleRelevanceFilter {

    FilterResult filter(String jobTitle);

    /**
     * Role relevance with source-scoped overrides. When the override carries replacement
     * include patterns they replace the global compiled set entirely (an empty exclude list
     * therefore means "no exclusions"); otherwise this is identical to {@link #filter(String)}.
     */
    FilterResult filter(String jobTitle, FilterOverrides overrides);
}
