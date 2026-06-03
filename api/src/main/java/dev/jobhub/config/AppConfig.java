package dev.jobhub.config;

import dev.jobhub.discovery.DiscoveryProperties;
import dev.jobhub.resolution.ResolutionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CrawlProperties.class, DiscoveryProperties.class, ResolutionProperties.class})
public class AppConfig {
}
