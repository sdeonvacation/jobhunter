package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.aggregator.CliStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IndeedSourceTest {

    private CliStrategy cliStrategy;
    private IndeedSource source;

    @BeforeEach
    void setUp() {
        cliStrategy = mock(CliStrategy.class);
        source = new IndeedSource(
                cliStrategy,
                List.of("backend engineer", "Spring Boot"),
                List.of("Germany", "remote"),
                25,
                24,
                12
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
    @DisplayName("frequencyHours() returns configured value")
    void frequencyHoursReturnsConfigured() {
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
    @DisplayName("buildContext() with custom values")
    void buildContextWithCustomValues() {
        IndeedSource customSource = new IndeedSource(
                cliStrategy, List.of("kotlin"), List.of("Netherlands"), 50, 12, 8);

        FetchContext context = customSource.buildContext();
        assertThat(context.keywords()).containsExactly("kotlin");
        assertThat(context.locations()).containsExactly("Netherlands");
        assertThat(context.maxResults()).isEqualTo(50);
        assertThat(context.config()).containsEntry("hours-old", 12);
        assertThat(customSource.frequencyHours()).isEqualTo(8);
    }
}
