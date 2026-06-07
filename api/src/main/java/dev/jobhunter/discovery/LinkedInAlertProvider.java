package dev.jobhunter.discovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Stub implementation for LinkedIn alert-based discovery.
 * Would parse Gmail for LinkedIn job alert emails when enabled.
 * Disabled by default.
 */
@Slf4j
@Component
public class LinkedInAlertProvider implements DiscoveryProvider {

    private final DiscoveryProperties properties;

    public LinkedInAlertProvider(DiscoveryProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "linkedin-alerts";
    }

    @Override
    public List<DiscoveredCompany> discover(DiscoveryQuery query) {
        if (!isHealthy()) {
            return List.of();
        }

        // Stub: would connect to Gmail API, parse LinkedIn alert emails,
        // extract company names and job URLs
        log.info("LinkedIn alerts provider not yet implemented");
        return List.of();
    }

    @Override
    public boolean isHealthy() {
        var config = properties.providers().get("linkedin-alerts");
        return config != null && config.enabled();
    }

    @Override
    public DiscoveryProviderStats getStats() {
        return new DiscoveryProviderStats(0, 0, 0, null, Duration.ZERO);
    }
}
