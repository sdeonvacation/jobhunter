package dev.jobhunter.source;

import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(AggregatorSourceProperties.class)
public class DynamicSourceConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(DynamicSourceConfigLoader.class);

    @Bean
    public List<SourceConfig> dynamicSources(AggregatorSourceProperties props, StrategyRegistry registry) {
        List<SourceConfig> sources = props.getSources().stream()
                .filter(AggregatorSourceProperties.SourceEntry::isEnabled)
                .filter(entry -> {
                    if (registry.getStrategy(entry.getStrategy()).isEmpty()) {
                        log.warn("Skipping source '{}': strategy '{}' not found in registry",
                                entry.getName(), entry.getStrategy());
                        return false;
                    }
                    return true;
                })
                .map(entry -> {
                    var strategy = registry.getStrategy(entry.getStrategy()).orElseThrow();
                    Map<String, Object> config = new HashMap<>(entry.getConfig());
                    config.put("url", entry.getUrl());
                    return (SourceConfig) new YamlSourceConfig(
                            entry.getName(),
                            JobSource.valueOf(entry.getJobSource()),
                            DiscoverySource.valueOf(entry.getDiscoverySource()),
                            strategy,
                            entry.getUrl(),
                            entry.getFrequencyHours(),
                            entry.getMaxResults(),
                            entry.isVisaExempt(),
                            config
                    );
                })
                .toList();

        log.info("Loaded {} dynamic aggregator sources from YAML config", sources.size());
        sources.forEach(s -> log.debug("  - {} (strategy={}, frequency={}h)",
                s.name(), s.strategy().name(), s.frequencyHours()));

        return sources;
    }
}
