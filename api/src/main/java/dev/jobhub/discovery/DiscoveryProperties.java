package dev.jobhub.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "discovery")
public record DiscoveryProperties(
        String schedule,
        Map<String, ProviderConfig> providers
) {

    public record ProviderConfig(
            boolean enabled,
            List<String> keywords,
            List<String> locations,
            List<String> postalCodes
    ) {
        public ProviderConfig {
            if (keywords == null) keywords = List.of();
            if (locations == null) locations = List.of();
            if (postalCodes == null) postalCodes = List.of();
        }
    }
}
