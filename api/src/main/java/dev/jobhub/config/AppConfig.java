package dev.jobhub.config;

import dev.jobhub.discovery.DiscoveryProperties;
import dev.jobhub.linkedin.LinkedInMcpProperties;
import dev.jobhub.resolution.ResolutionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CrawlProperties.class, DiscoveryProperties.class, ResolutionProperties.class, LinkedInMcpProperties.class})
public class AppConfig {
}
