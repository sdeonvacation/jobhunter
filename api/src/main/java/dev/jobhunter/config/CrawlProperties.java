package dev.jobhunter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawl")
public record CrawlProperties(
        int defaultFrequencyHours,
        int highPriorityFrequencyHours,
        int timeoutSeconds
) {
    public CrawlProperties {
        if (defaultFrequencyHours <= 0) defaultFrequencyHours = 4;
        if (highPriorityFrequencyHours <= 0) highPriorityFrequencyHours = 2;
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}
