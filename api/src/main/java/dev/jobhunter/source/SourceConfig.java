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

    int frequencyHours();

    boolean isEnabled();
}
