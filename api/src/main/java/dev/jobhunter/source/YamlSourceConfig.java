package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;

import java.util.List;
import java.util.Map;

public record YamlSourceConfig(
        String name,
        JobSource sourceType,
        DiscoverySource discoverySource,
        FetchStrategy strategy,
        String url,
        int frequencyHours,
        int maxResults,
        Map<String, Object> extraConfig
) implements SourceConfig {

    @Override
    public FetchContext buildContext() {
        return FetchContext.forSearch(
                List.of(),
                List.of(),
                maxResults,
                3,
                extraConfig != null ? extraConfig : Map.of("url", url)
        );
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
