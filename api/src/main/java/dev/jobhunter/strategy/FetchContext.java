package dev.jobhunter.strategy;

import dev.jobhunter.model.CareerEndpoint;

import java.util.List;
import java.util.Map;

public record FetchContext(
    CareerEndpoint endpoint,
    List<String> keywords,
    List<String> locations,
    int maxResults,
    int maxPages,
    Map<String, Object> config
) {

    public static FetchContext forEndpoint(CareerEndpoint endpoint) {
        return new FetchContext(endpoint, null, null, 200, 10, Map.of());
    }

    public static FetchContext forSearch(List<String> keywords, List<String> locations,
                                         int maxResults, int maxPages, Map<String, Object> config) {
        return new FetchContext(null, keywords, locations, maxResults, maxPages, config);
    }
}
