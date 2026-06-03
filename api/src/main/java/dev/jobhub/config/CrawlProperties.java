package dev.jobhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawl")
public record CrawlProperties(
        int defaultFrequencyHours,
        int highPriorityFrequencyHours,
        int batchSize,
        int timeoutSeconds
) {
    public CrawlProperties {
        if (defaultFrequencyHours <= 0) defaultFrequencyHours = 4;
        if (highPriorityFrequencyHours <= 0) highPriorityFrequencyHours = 2;
        if (batchSize <= 0) batchSize = 50;
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}
