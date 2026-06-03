package dev.jobhub.discovery;

import java.time.Duration;
import java.time.LocalDateTime;

public record DiscoveryProviderStats(
        int totalCalls,
        int successfulCalls,
        int companiesDiscovered,
        LocalDateTime lastCallAt,
        Duration avgLatency
) {}
