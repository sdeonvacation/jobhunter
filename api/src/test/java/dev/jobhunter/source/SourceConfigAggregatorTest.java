package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceConfigAggregatorTest {

    @Mock private ObjectProvider<SourceConfig> componentSources;
    @Mock private FetchStrategy fetchStrategy;

    private final SourceConfigAggregator aggregator = new SourceConfigAggregator();

    @Test
    void allSources_mergesComponentAndDynamicSources() {
        SourceConfig componentSource = createSource("linkedin", JobSource.LINKEDIN);
        SourceConfig dynamicSource = createSource("arbeitnow", JobSource.ARBEITNOW);

        when(componentSources.orderedStream()).thenReturn(Stream.of(componentSource));
        List<SourceConfig> dynamicSources = List.of(dynamicSource);

        List<SourceConfig> result = aggregator.allSources(componentSources, dynamicSources);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("linkedin");
        assertThat(result.get(1).name()).isEqualTo("arbeitnow");
    }

    @Test
    void allSources_dynamicSourcesNull_returnsOnlyComponents() {
        SourceConfig componentSource = createSource("linkedin", JobSource.LINKEDIN);
        when(componentSources.orderedStream()).thenReturn(Stream.of(componentSource));

        List<SourceConfig> result = aggregator.allSources(componentSources, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("linkedin");
    }

    @Test
    void allSources_noComponentSources_returnsOnlyDynamic() {
        SourceConfig dynamicSource = createSource("arbeitnow", JobSource.ARBEITNOW);
        when(componentSources.orderedStream()).thenReturn(Stream.empty());

        List<SourceConfig> result = aggregator.allSources(componentSources, List.of(dynamicSource));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("arbeitnow");
    }

    @Test
    void allSources_bothEmpty_returnsEmptyList() {
        when(componentSources.orderedStream()).thenReturn(Stream.empty());

        List<SourceConfig> result = aggregator.allSources(componentSources, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void allSources_returnedListIsUnmodifiable() {
        when(componentSources.orderedStream()).thenReturn(Stream.empty());

        List<SourceConfig> result = aggregator.allSources(componentSources, List.of());

        assertThat(result).isUnmodifiable();
    }

    private SourceConfig createSource(String name, JobSource jobSource) {
        return new SourceConfig() {
            @Override public String name() { return name; }
            @Override public JobSource sourceType() { return jobSource; }
            @Override public DiscoverySource discoverySource() { return DiscoverySource.JOBSPY; }
            @Override public FetchStrategy strategy() { return fetchStrategy; }
            @Override public FetchContext buildContext() { return null; }
            @Override public int frequencyHours() { return 6; }
            @Override public boolean isEnabled() { return true; }
        };
    }
}
