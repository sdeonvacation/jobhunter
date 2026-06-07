package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.aggregator.McpStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LinkedInSourceTest {

    private McpStrategy mcpStrategy;
    private LinkedInSource source;

    @BeforeEach
    void setUp() {
        mcpStrategy = mock(McpStrategy.class);
        source = new LinkedInSource(
                mcpStrategy,
                List.of("backend engineer", "Java developer"),
                List.of("Germany", "Netherlands"),
                200,
                6,
                "week"
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
    @DisplayName("frequencyHours() returns configured value")
    void frequencyHoursReturnsConfigured() {
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
    @DisplayName("buildContext() with custom frequency")
    void buildContextWithCustomFrequency() {
        LinkedInSource customSource = new LinkedInSource(
                mcpStrategy, List.of("kotlin"), List.of("Berlin"), 100, 12, "day");

        assertThat(customSource.frequencyHours()).isEqualTo(12);
        FetchContext context = customSource.buildContext();
        assertThat(context.config()).containsEntry("date-posted", "day");
        assertThat(context.maxResults()).isEqualTo(100);
    }
}
