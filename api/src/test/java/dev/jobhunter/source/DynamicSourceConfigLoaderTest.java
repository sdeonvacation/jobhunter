package dev.jobhunter.source;

import dev.jobhunter.filter.FilterOverrides;
import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.service.PersonalProfileLoader;
import dev.jobhunter.strategy.FetchStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicSourceConfigLoaderTest {

    private DynamicSourceConfigLoader loader;
    private StrategyRegistry registry;
    private PersonalProfileLoader profileLoader;
    private FetchStrategy aiStrategy;
    private FetchStrategy restApiStrategy;

    @BeforeEach
    void setUp() {
        loader = new DynamicSourceConfigLoader();
        registry = mock(StrategyRegistry.class);
        profileLoader = mock(PersonalProfileLoader.class);
        aiStrategy = mock(FetchStrategy.class);
        restApiStrategy = mock(FetchStrategy.class);

        when(aiStrategy.name()).thenReturn("ai");
        when(restApiStrategy.name()).thenReturn("rest-api");
        when(registry.getStrategy("ai")).thenReturn(Optional.of(aiStrategy));
        when(registry.getStrategy("rest-api")).thenReturn(Optional.of(restApiStrategy));
        lenient().when(profileLoader.getSourceFilterOverrides()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("loads enabled sources from properties")
    void loadsEnabledSources() {
        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("berlinstartupjobs", "ai", "BERLIN_STARTUP_JOBS", "BERLIN_STARTUP_JOBS",
                        "https://berlinstartupjobs.com/engineering/", 12, 30, true),
                createEntry("arbeitnow", "rest-api", "ARBEITNOW", "ARBEITNOW",
                        "https://www.arbeitnow.com/api/job-board-api", 6, 50, true)
        ));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).hasSize(2);

        SourceConfig bsj = sources.get(0);
        assertThat(bsj.name()).isEqualTo("berlinstartupjobs");
        assertThat(bsj.sourceType()).isEqualTo(JobSource.BERLIN_STARTUP_JOBS);
        assertThat(bsj.discoverySource()).isEqualTo(DiscoverySource.BERLIN_STARTUP_JOBS);
        assertThat(bsj.strategy()).isSameAs(aiStrategy);
        assertThat(bsj.frequencyHours()).isEqualTo(12);
        assertThat(bsj.isEnabled()).isTrue();

        SourceConfig arb = sources.get(1);
        assertThat(arb.name()).isEqualTo("arbeitnow");
        assertThat(arb.sourceType()).isEqualTo(JobSource.ARBEITNOW);
        assertThat(arb.discoverySource()).isEqualTo(DiscoverySource.ARBEITNOW);
        assertThat(arb.strategy()).isSameAs(restApiStrategy);
        assertThat(arb.frequencyHours()).isEqualTo(6);
    }

    @Test
    @DisplayName("filters out disabled sources")
    void filtersDisabledSources() {
        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("berlinstartupjobs", "ai", "BERLIN_STARTUP_JOBS", "BERLIN_STARTUP_JOBS",
                        "https://berlinstartupjobs.com/engineering/", 12, 30, true),
                createEntry("disabled-source", "rest-api", "ARBEITNOW", "ARBEITNOW",
                        "https://example.com/api", 6, 50, false)
        ));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).name()).isEqualTo("berlinstartupjobs");
    }

    @Test
    @DisplayName("returns empty list when no sources configured")
    void emptyWhenNoSources() {
        AggregatorSourceProperties props = new AggregatorSourceProperties();

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).isEmpty();
    }

    @Test
    @DisplayName("skips source when strategy not found in registry (warn, don't throw)")
    void skipsSourceWhenStrategyNotFound() {
        when(registry.getStrategy("nonexistent")).thenReturn(Optional.empty());

        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("bad", "nonexistent", "ARBEITNOW", "ARBEITNOW",
                        "https://example.com", 12, 50, true)
        ));

        // No exception thrown; source is silently skipped
        List<SourceConfig> result = loader.dynamicSources(props, registry, profileLoader);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("buildContext includes URL in config map")
    void buildContextIncludesUrl() {
        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("test", "ai", "BERLIN_STARTUP_JOBS", "BERLIN_STARTUP_JOBS",
                        "https://example.com/jobs", 12, 25, true)
        ));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);
        var context = sources.get(0).buildContext();

        assertThat(context.config()).containsEntry("url", "https://example.com/jobs");
        assertThat(context.maxResults()).isEqualTo(25);
        assertThat(context.maxPages()).isEqualTo(3);
        assertThat(context.keywords()).isEmpty();
        assertThat(context.locations()).isEmpty();
    }

    @Test
    @DisplayName("resolves per-source filter override by config name; untagged source → NONE")
    void resolvesPerSourceFilterOverride() {
        FilterOverrides override = new FilterOverrides(List.of("software"), List.of(), true);
        when(profileLoader.getSourceFilterOverrides()).thenReturn(Map.of("berlinstartupjobs", override));

        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("berlinstartupjobs", "ai", "BERLIN_STARTUP_JOBS", "BERLIN_STARTUP_JOBS",
                        "https://berlinstartupjobs.com/engineering/", 12, 30, true),
                createEntry("arbeitnow", "rest-api", "ARBEITNOW", "ARBEITNOW",
                        "https://www.arbeitnow.com/api/job-board-api", 6, 50, true)
        ));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).filterOverrides()).isSameAs(override);
        assertThat(sources.get(1).filterOverrides()).isEqualTo(FilterOverrides.NONE);
    }

    @Test
    @DisplayName("unknown source name → NONE (no override applied)")
    void unknownSourceNameReturnsNone() {
        FilterOverrides override = new FilterOverrides(List.of("software"), List.of(), true);
        when(profileLoader.getSourceFilterOverrides()).thenReturn(Map.of("some-other-source", override));

        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("berlinstartupjobs", "ai", "BERLIN_STARTUP_JOBS", "BERLIN_STARTUP_JOBS",
                        "https://berlinstartupjobs.com/engineering/", 12, 30, true)
        ));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).filterOverrides()).isEqualTo(FilterOverrides.NONE);
    }

    @Test
    @DisplayName("passes translate-titles flag through to the source config")
    void passesTranslateTitlesFlag() {
        var entry = createEntry("wissenschaftsstellen", "ai", "WISSENSCHAFTSSTELLEN", "WISSENSCHAFTSSTELLEN",
                "https://wissenschaftsstellen.de", 12, 300, true);
        entry.setTranslateTitles(true);

        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(entry));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).translateTitles()).isTrue();
    }

    @Test
    @DisplayName("translate-titles defaults to false when unset")
    void translateTitlesDefaultsToFalse() {
        AggregatorSourceProperties props = new AggregatorSourceProperties();
        props.setSources(List.of(
                createEntry("berlinstartupjobs", "ai", "BERLIN_STARTUP_JOBS", "BERLIN_STARTUP_JOBS",
                        "https://berlinstartupjobs.com/engineering/", 12, 30, true)
        ));

        List<SourceConfig> sources = loader.dynamicSources(props, registry, profileLoader);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).translateTitles()).isFalse();
    }

    private AggregatorSourceProperties.SourceEntry createEntry(
            String name, String strategy, String jobSource, String discoverySource,
            String url, int frequencyHours, int maxResults, boolean enabled) {
        var entry = new AggregatorSourceProperties.SourceEntry();
        entry.setName(name);
        entry.setStrategy(strategy);
        entry.setJobSource(jobSource);
        entry.setDiscoverySource(discoverySource);
        entry.setUrl(url);
        entry.setFrequencyHours(frequencyHours);
        entry.setMaxResults(maxResults);
        entry.setEnabled(enabled);
        return entry;
    }
}
