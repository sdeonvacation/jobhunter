package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.aggregator.McpStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Source configuration for LinkedIn job discovery via MCP server.
 */
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInSource implements SourceConfig {

    private final McpStrategy mcpStrategy;
    private final List<String> keywords;
    private final List<String> locations;
    private final int maxResults;
    private final int frequencyHours;
    private final String datePosted;

    public LinkedInSource(
            McpStrategy mcpStrategy,
            @Value("${discovery.providers.linkedin.keywords:}") List<String> keywords,
            @Value("${discovery.providers.linkedin.locations:}") List<String> locations,
            @Value("${discovery.providers.linkedin.max-results:200}") int maxResults,
            @Value("${discovery.providers.linkedin.frequency-hours:6}") int frequencyHours,
            @Value("${discovery.providers.linkedin.date-posted:week}") String datePosted) {
        this.mcpStrategy = mcpStrategy;
        this.keywords = keywords;
        this.locations = locations;
        this.maxResults = maxResults;
        this.frequencyHours = frequencyHours;
        this.datePosted = datePosted;
    }

    @Override
    public String name() {
        return "linkedin";
    }

    @Override
    public JobSource sourceType() {
        return JobSource.LINKEDIN;
    }

    @Override
    public DiscoverySource discoverySource() {
        return DiscoverySource.LINKEDIN;
    }

    @Override
    public FetchStrategy strategy() {
        return mcpStrategy;
    }

    @Override
    public FetchContext buildContext() {
        Map<String, Object> config = Map.of("date-posted", datePosted);
        return FetchContext.forSearch(keywords, locations, maxResults, 10, config);
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
