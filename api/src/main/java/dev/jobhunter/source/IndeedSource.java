package dev.jobhunter.source;

import dev.jobhunter.discovery.DiscoveryProperties;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.aggregator.CliStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Source configuration for Indeed job discovery via jobspy-js CLI.
 */
@Component
@ConditionalOnProperty(prefix = "discovery.providers.jobspy", name = "enabled", havingValue = "true")
public class IndeedSource implements SourceConfig {

    private static final int DEFAULT_RESULTS_WANTED = 25;
    private static final int DEFAULT_HOURS_OLD = 24;
    private static final int DEFAULT_FREQUENCY_HOURS = 12;

    private final CliStrategy cliStrategy;
    private final List<String> keywords;
    private final List<String> locations;
    private final int resultsWanted;
    private final int hoursOld;
    private final int frequencyHours;

    public IndeedSource(CliStrategy cliStrategy, DiscoveryProperties discoveryProperties) {
        this.cliStrategy = cliStrategy;
        DiscoveryProperties.ProviderConfig config = discoveryProperties.providers().get("jobspy");
        if (config != null) {
            this.keywords = config.keywords();
            this.locations = config.locations();
            this.resultsWanted = config.resultsWanted() != null ? config.resultsWanted() : DEFAULT_RESULTS_WANTED;
            this.hoursOld = config.hoursOld() != null ? config.hoursOld() : DEFAULT_HOURS_OLD;
            this.frequencyHours = config.frequencyHours() != null ? config.frequencyHours() : DEFAULT_FREQUENCY_HOURS;
        } else {
            this.keywords = List.of();
            this.locations = List.of();
            this.resultsWanted = DEFAULT_RESULTS_WANTED;
            this.hoursOld = DEFAULT_HOURS_OLD;
            this.frequencyHours = DEFAULT_FREQUENCY_HOURS;
        }
    }

    @Override
    public String name() {
        return "indeed";
    }

    @Override
    public JobSource sourceType() {
        return JobSource.INDEED;
    }

    @Override
    public DiscoverySource discoverySource() {
        return DiscoverySource.JOBSPY;
    }

    @Override
    public FetchStrategy strategy() {
        return cliStrategy;
    }

    @Override
    public FetchContext buildContext() {
        Map<String, Object> config = Map.of(
                "hours-old", hoursOld,
                "timeout-seconds", 60
        );
        return FetchContext.forSearch(keywords, locations, resultsWanted, 1, config);
    }

    @Override
    public int frequencyHours() {
        return frequencyHours;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
