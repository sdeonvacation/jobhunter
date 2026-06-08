package dev.jobhunter.config;

import dev.jobhunter.discovery.DiscoveryProperties;
import dev.jobhunter.linkedin.LinkedInMcpProperties;
import dev.jobhunter.resolution.ResolutionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PipelineProperties.class, DiscoveryProperties.class, ResolutionProperties.class, LinkedInMcpProperties.class})
public class AppConfig {
}
