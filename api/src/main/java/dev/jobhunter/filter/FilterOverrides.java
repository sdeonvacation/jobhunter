package dev.jobhunter.filter;

import java.util.List;

/**
 * Source-scoped filter overrides threaded through the aggregator ingest path.
 *
 * <p>A value of {@link #NONE} (the universal default) means every filter step behaves
 * exactly as before: the global role patterns are used and the language filter runs.
 */
public record FilterOverrides(
        List<String> roleIncludePatterns,
        List<String> roleExcludeKeywords,
        boolean languageExempt
) {

    /** No override — the global filter configuration applies unchanged. */
    public static final FilterOverrides NONE = new FilterOverrides(List.of(), List.of(), false);

    /**
     * A role override exists only when replacement include patterns are supplied. An empty
     * exclude list under an active override means "no role exclusions" (not "no override").
     */
    public boolean hasRoleOverride() {
        return roleIncludePatterns != null && !roleIncludePatterns.isEmpty();
    }
}
