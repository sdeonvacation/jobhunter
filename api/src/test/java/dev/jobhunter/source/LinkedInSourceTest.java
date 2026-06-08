package dev.jobhunter.source;

import dev.jobhunter.discovery.DiscoveryProperties;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.aggregator.McpStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LinkedInSourceTest {

    private McpStrategy mcpStrategy;
    private LinkedInSource source;

    private DiscoveryProperties buildProperties(List<String> keywords, List<String> locations) {
        return buildProperties(keywords, locations, null, null, null);
    }

    private DiscoveryProperties buildProperties(List<String> keywords, List<String> locations,
                                                 Integer maxResults, Integer frequencyHours, String datePosted) {
        var config = new DiscoveryProperties.ProviderConfig(
                true, keywords, locations, null, maxResults, frequencyHours, null, null, datePosted);
        return new DiscoveryProperties(null, Map.of("linkedin", config));
    }

    @BeforeEach
    void setUp() {
        mcpStrategy = mock(McpStrategy.class);
        source = new LinkedInSource(
                mcpStrategy,
                buildProperties(List.of("backend engineer", "Java developer"), List.of("Germany", "Netherlands"))
        );
    }

    @Test
    @DisplayName("name() returns linkedin")
    void nameReturnsLinkedin() {
        assertThat(source.name()).isEqualTo("linkedin");
    }

    @Test
    @DisplayName("sourceType() returns LINKEDIN")
    void sourceTypeReturnsLinkedIn() {
        assertThat(source.sourceType()).isEqualTo(JobSource.LINKEDIN);
    }

    @Test
    @DisplayName("discoverySource() returns LINKEDIN")
    void discoverySourceReturnsLinkedIn() {
        assertThat(source.discoverySource()).isEqualTo(DiscoverySource.LINKEDIN);
    }

    @Test
    @DisplayName("strategy() returns injected McpStrategy")
    void strategyReturnsMcpStrategy() {
        assertThat(source.strategy()).isSameAs(mcpStrategy);
    }

    @Test
    @DisplayName("frequencyHours() returns default when not configured")
    void frequencyHoursReturnsDefault() {
        assertThat(source.frequencyHours()).isEqualTo(6);
    }

    @Test
    @DisplayName("isEnabled() returns true")
    void isEnabledReturnsTrue() {
        assertThat(source.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("buildContext() creates FetchContext with keywords, locations, and config")
    void buildContextCreatesCorrectFetchContext() {
        FetchContext context = source.buildContext();

        assertThat(context.keywords()).containsExactly("backend engineer", "Java developer");
        assertThat(context.locations()).containsExactly("Germany", "Netherlands");
        assertThat(context.maxResults()).isEqualTo(200);
        assertThat(context.maxPages()).isEqualTo(10);
        assertThat(context.config()).containsEntry("date-posted", "week");
    }

    @Test
    @DisplayName("buildContext() uses configured values from ProviderConfig")
    void buildContextWithConfiguredValues() {
        LinkedInSource customSource = new LinkedInSource(
                mcpStrategy,
                buildProperties(List.of("kotlin"), List.of("Berlin"), 100, 12, "day")
        );

        assertThat(customSource.frequencyHours()).isEqualTo(12);
        FetchContext context = customSource.buildContext();
        assertThat(context.keywords()).containsExactly("kotlin");
        assertThat(context.locations()).containsExactly("Berlin");
        assertThat(context.config()).containsEntry("date-posted", "day");
        assertThat(context.maxResults()).isEqualTo(100);
    }

    @Test
    @DisplayName("buildContext() returns empty lists when provider config missing")
    void buildContextWithMissingProviderConfig() {
        DiscoveryProperties emptyProps = new DiscoveryProperties(null, Map.of());
        LinkedInSource emptySource = new LinkedInSource(mcpStrategy, emptyProps);

        FetchContext context = emptySource.buildContext();
        assertThat(context.keywords()).isEmpty();
        assertThat(context.locations()).isEmpty();
        assertThat(context.maxResults()).isEqualTo(200);
        assertThat(context.config()).containsEntry("date-posted", "week");
    }
}
