package dev.jobhunter.source;

import dev.jobhunter.filter.FilterOverrides;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;

import java.util.Arrays;
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
        boolean visaExempt,
        Map<String, Object> extraConfig,
        FilterOverrides filterOverrides,
        boolean translateTitles
) implements SourceConfig {

    public YamlSourceConfig {
        if (filterOverrides == null) {
            filterOverrides = FilterOverrides.NONE;
        }
    }

    /** Backward-compatible constructor: no source-scoped filter override, no title translation. */
    public YamlSourceConfig(String name,
                            JobSource sourceType,
                            DiscoverySource discoverySource,
                            FetchStrategy strategy,
                            String url,
                            int frequencyHours,
                            int maxResults,
                            boolean visaExempt,
                            Map<String, Object> extraConfig) {
        this(name, sourceType, discoverySource, strategy, url, frequencyHours, maxResults,
                visaExempt, extraConfig, FilterOverrides.NONE, false);
    }

    /** Backward-compatible constructor: source-scoped filter override, no title translation. */
    public YamlSourceConfig(String name,
                            JobSource sourceType,
                            DiscoverySource discoverySource,
                            FetchStrategy strategy,
                            String url,
                            int frequencyHours,
                            int maxResults,
                            boolean visaExempt,
                            Map<String, Object> extraConfig,
                            FilterOverrides filterOverrides) {
        this(name, sourceType, discoverySource, strategy, url, frequencyHours, maxResults,
                visaExempt, extraConfig, filterOverrides, false);
    }

    @Override
    public FetchContext buildContext() {
        List<String> keywords = List.of();
        if (extraConfig != null && extraConfig.containsKey("queries")) {
            String raw = (String) extraConfig.get("queries");
            if (raw != null && !raw.isBlank()) {
                keywords = Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
            }
        }
        return FetchContext.forSearch(
                keywords,
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
