package dev.jobhub.discovery;

import java.util.List;

public interface DiscoveryProvider {

    String name();

    List<DiscoveredCompany> discover(DiscoveryQuery query);

    boolean isHealthy();

    DiscoveryProviderStats getStats();
}
