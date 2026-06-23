package dev.jobhunter.source;

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
}
