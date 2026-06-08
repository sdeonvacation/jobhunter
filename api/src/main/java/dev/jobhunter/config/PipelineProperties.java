package dev.jobhunter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pipeline")
public record PipelineProperties(
        String schedule,
        int timeoutSeconds
) {
    public PipelineProperties {
        if (schedule == null || schedule.isBlank()) schedule = "0 0 7,13,19 * * ?";
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}
