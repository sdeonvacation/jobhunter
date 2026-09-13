package dev.jobhunter.source;

import dev.jobhunter.filter.FilterOverrides;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;

public interface SourceConfig {

    String name();

    JobSource sourceType();

    DiscoverySource discoverySource();

    FetchStrategy strategy();

    FetchContext buildContext();

    /** Stored for reference only — not used for gating. PipelineScheduler runs all sources on every tick. */
    int frequencyHours();

    boolean isEnabled();

    /** Sources targeting expats/internationals skip visa sponsorship checks. */
    default boolean visaExempt() { return false; }

    /**
     * Sources whose stored job titles are non-English opt in to a single batched English
     * translation per ingest. Defaults to {@code false}, so other sources are unchanged.
     */
    default boolean translateTitles() { return false; }

    /**
     * Source-scoped filter overrides (role pattern replacement + language exemption).
     * Defaults to {@link FilterOverrides#NONE}, so sources without an override are unchanged.
     */
    default FilterOverrides filterOverrides() { return FilterOverrides.NONE; }
}
