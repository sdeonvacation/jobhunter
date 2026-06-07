package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.aggregator.CliStrategy;
import org.springframework.beans.factory.annotation.Value;
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

    private final CliStrategy cliStrategy;
    private final List<String> keywords;
    private final List<String> locations;
    private final int resultsWanted;
    private final int hoursOld;
    private final int frequencyHours;

    public IndeedSource(
            CliStrategy cliStrategy,
            @Value("${discovery.providers.jobspy.keywords:}") List<String> keywords,
            @Value("${discovery.providers.jobspy.locations:}") List<String> locations,
            @Value("${discovery.providers.jobspy.results-wanted:25}") int resultsWanted,
            @Value("${discovery.providers.jobspy.hours-old:24}") int hoursOld,
            @Value("${discovery.providers.jobspy.frequency-hours:12}") int frequencyHours) {
        this.cliStrategy = cliStrategy;
        this.keywords = keywords;
        this.locations = locations;
        this.resultsWanted = resultsWanted;
        this.hoursOld = hoursOld;
        this.frequencyHours = frequencyHours;
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
