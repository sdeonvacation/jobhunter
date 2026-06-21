package dev.jobhunter.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "aggregator")
public class AggregatorSourceProperties {

    private List<SourceEntry> sources = List.of();

    public List<SourceEntry> getSources() {
        return sources;
    }

    public void setSources(List<SourceEntry> sources) {
        this.sources = sources;
    }

    public static class SourceEntry {
        private String name;
        private String strategy;
        private String jobSource;
        private String discoverySource;
        private String url;
        private int frequencyHours = 12;
        private int maxResults = 50;
        private boolean enabled = true;
        private Map<String, String> config = new HashMap<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }

        public String getJobSource() { return jobSource; }
        public void setJobSource(String jobSource) { this.jobSource = jobSource; }

        public String getDiscoverySource() { return discoverySource; }
        public void setDiscoverySource(String discoverySource) { this.discoverySource = discoverySource; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public int getFrequencyHours() { return frequencyHours; }
        public void setFrequencyHours(int frequencyHours) { this.frequencyHours = frequencyHours; }

        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Map<String, String> getConfig() { return config; }
        public void setConfig(Map<String, String> config) { this.config = config; }
    }
}
