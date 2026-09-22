package dev.jobhunter.source;

import dev.jobhunter.discovery.DiscoveryProperties;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.aggregator.McpStrategy;
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

    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int DEFAULT_FREQUENCY_HOURS = 6;
    private static final String DEFAULT_DATE_POSTED = "week";
    private static final int DEFAULT_MAX_PAGES = 2;
    private static final int DEFAULT_PAIRS_PER_RUN = 12;
    private static final int DEFAULT_SEARCH_RESERVE = 5;

    private final McpStrategy mcpStrategy;
    private final List<String> keywords;
    private final List<String> locations;
    private final int maxResults;
    private final int frequencyHours;
    private final String datePosted;
    private final int maxPages;
    private final int pairsPerRun;
    private final int searchReserve;

    public LinkedInSource(McpStrategy mcpStrategy, DiscoveryProperties discoveryProperties) {
        this.mcpStrategy = mcpStrategy;
        DiscoveryProperties.ProviderConfig config = discoveryProperties.providers().get("linkedin");
        if (config != null) {
            this.keywords = config.keywords();
            this.locations = config.locations();
            this.maxResults = config.maxResults() != null ? config.maxResults() : DEFAULT_MAX_RESULTS;
            this.frequencyHours = config.frequencyHours() != null ? config.frequencyHours() : DEFAULT_FREQUENCY_HOURS;
            this.datePosted = config.datePosted() != null ? config.datePosted() : DEFAULT_DATE_POSTED;
            this.maxPages = config.maxPages() != null ? config.maxPages() : DEFAULT_MAX_PAGES;
            this.pairsPerRun = config.pairsPerRun() != null ? config.pairsPerRun() : DEFAULT_PAIRS_PER_RUN;
            this.searchReserve = config.searchReserve() != null ? config.searchReserve() : DEFAULT_SEARCH_RESERVE;
        } else {
            this.keywords = List.of();
            this.locations = List.of();
            this.maxResults = DEFAULT_MAX_RESULTS;
            this.frequencyHours = DEFAULT_FREQUENCY_HOURS;
            this.datePosted = DEFAULT_DATE_POSTED;
            this.maxPages = DEFAULT_MAX_PAGES;
            this.pairsPerRun = DEFAULT_PAIRS_PER_RUN;
            this.searchReserve = DEFAULT_SEARCH_RESERVE;
        }
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
        Map<String, Object> config = Map.of(
                "date-posted", datePosted,
                "max-pages", maxPages,
                "pairs-per-run", pairsPerRun,
                "search-reserve", searchReserve);
        return FetchContext.forSearch(keywords, locations, maxResults, maxPages, config);
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
