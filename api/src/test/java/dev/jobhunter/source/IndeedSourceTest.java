package dev.jobhunter.source;

import dev.jobhunter.discovery.DiscoveryProperties;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.aggregator.CliStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IndeedSourceTest {

    private CliStrategy cliStrategy;
    private IndeedSource source;

    private DiscoveryProperties buildProperties(List<String> keywords, List<String> locations) {
        return buildProperties(keywords, locations, null, null, null);
    }

    private DiscoveryProperties buildProperties(List<String> keywords, List<String> locations,
                                                 Integer resultsWanted, Integer hoursOld, Integer frequencyHours) {
        var config = new DiscoveryProperties.ProviderConfig(
                true, keywords, locations, null, null, frequencyHours, resultsWanted, hoursOld, null);
        return new DiscoveryProperties(null, Map.of("jobspy", config));
    }

    @BeforeEach
    void setUp() {
        cliStrategy = mock(CliStrategy.class);
        source = new IndeedSource(
                cliStrategy,
                buildProperties(List.of("backend engineer", "Spring Boot"), List.of("Germany", "remote"))
        );
    }

    @Test
    @DisplayName("name() returns indeed")
    void nameReturnsIndeed() {
        assertThat(source.name()).isEqualTo("indeed");
    }

    @Test
    @DisplayName("sourceType() returns INDEED")
    void sourceTypeReturnsIndeed() {
        assertThat(source.sourceType()).isEqualTo(JobSource.INDEED);
    }

    @Test
    @DisplayName("discoverySource() returns JOBSPY")
    void discoverySourceReturnsJobspy() {
        assertThat(source.discoverySource()).isEqualTo(DiscoverySource.JOBSPY);
    }

    @Test
    @DisplayName("strategy() returns injected CliStrategy")
    void strategyReturnsCliStrategy() {
        assertThat(source.strategy()).isSameAs(cliStrategy);
    }

    @Test
    @DisplayName("frequencyHours() returns default when not configured")
    void frequencyHoursReturnsDefault() {
        assertThat(source.frequencyHours()).isEqualTo(12);
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

        assertThat(context.keywords()).containsExactly("backend engineer", "Spring Boot");
        assertThat(context.locations()).containsExactly("Germany", "remote");
        assertThat(context.maxResults()).isEqualTo(25);
        assertThat(context.maxPages()).isEqualTo(1);
        assertThat(context.config()).containsEntry("hours-old", 24);
        assertThat(context.config()).containsEntry("timeout-seconds", 60);
    }

    @Test
    @DisplayName("buildContext() uses configured values from ProviderConfig")
    void buildContextWithConfiguredValues() {
        IndeedSource customSource = new IndeedSource(
                cliStrategy,
                buildProperties(List.of("kotlin"), List.of("Netherlands"), 50, 12, 8)
        );

        FetchContext context = customSource.buildContext();
        assertThat(context.keywords()).containsExactly("kotlin");
        assertThat(context.locations()).containsExactly("Netherlands");
        assertThat(context.maxResults()).isEqualTo(50);
        assertThat(context.config()).containsEntry("hours-old", 12);
        assertThat(customSource.frequencyHours()).isEqualTo(8);
    }

    @Test
    @DisplayName("buildContext() returns empty lists when provider config missing")
    void buildContextWithMissingProviderConfig() {
        DiscoveryProperties emptyProps = new DiscoveryProperties(null, Map.of());
        IndeedSource emptySource = new IndeedSource(cliStrategy, emptyProps);

        FetchContext context = emptySource.buildContext();
        assertThat(context.keywords()).isEmpty();
        assertThat(context.locations()).isEmpty();
        assertThat(context.maxResults()).isEqualTo(25);
        assertThat(context.config()).containsEntry("hours-old", 24);
    }
}
