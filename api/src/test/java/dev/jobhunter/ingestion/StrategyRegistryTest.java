package dev.jobhunter.ingestion;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyRegistryTest {

    @Test
    void getStrategy_byType_returnsMatchingStrategy() {
        FetchStrategy strategy = mock(FetchStrategy.class);
        when(strategy.name()).thenReturn("greenhouse-strategy");
        when(strategy.supportedTypes()).thenReturn(Set.of(AtsType.GREENHOUSE));

        var registry = new StrategyRegistry(List.of(strategy));

        assertThat(registry.getStrategy(AtsType.GREENHOUSE)).contains(strategy);
        assertThat(registry.getStrategy(AtsType.LEVER)).isEmpty();
    }

    @Test
    void getStrategy_byName_returnsMatchingStrategy() {
        FetchStrategy strategy = mock(FetchStrategy.class);
        when(strategy.name()).thenReturn("test-strategy");
        when(strategy.supportedTypes()).thenReturn(Set.of(AtsType.LEVER));

        var registry = new StrategyRegistry(List.of(strategy));

        assertThat(registry.getStrategy("test-strategy")).contains(strategy);
        assertThat(registry.getStrategy("nonexistent")).isEmpty();
    }

    @Test
    void supportedTypes_returnsAllRegisteredTypes() {
        FetchStrategy s1 = mock(FetchStrategy.class);
        when(s1.name()).thenReturn("s1");
        when(s1.supportedTypes()).thenReturn(Set.of(AtsType.GREENHOUSE));

        FetchStrategy s2 = mock(FetchStrategy.class);
        when(s2.name()).thenReturn("s2");
        when(s2.supportedTypes()).thenReturn(Set.of(AtsType.LEVER));

        var registry = new StrategyRegistry(List.of(s1, s2));

        assertThat(registry.supportedTypes()).contains(AtsType.GREENHOUSE, AtsType.LEVER);
    }

    @Test
    void emptyStrategies_returnsEmptySets() {
        var registry = new StrategyRegistry(List.of());

        assertThat(registry.supportedTypes()).isEmpty();
        assertThat(registry.getStrategy(AtsType.GREENHOUSE)).isEmpty();
        assertThat(registry.getStrategy("any")).isEmpty();
    }

    @Test
    void strategy_supportingMultipleTypes_registeredForAll() {
        FetchStrategy lever = mock(FetchStrategy.class);
        when(lever.name()).thenReturn("lever");
        when(lever.supportedTypes()).thenReturn(Set.of(AtsType.LEVER, AtsType.LEVER_EU));

        var registry = new StrategyRegistry(List.of(lever));

        assertThat(registry.getStrategy(AtsType.LEVER)).contains(lever);
        assertThat(registry.getStrategy(AtsType.LEVER_EU)).contains(lever);
    }
}
