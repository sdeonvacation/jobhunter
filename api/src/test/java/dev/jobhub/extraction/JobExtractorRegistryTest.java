package dev.jobhub.extraction;

import dev.jobhub.model.enums.AtsType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobExtractorRegistryTest {

    @Test
    void getExtractor_registeredType_returnsExtractor() {
        JobExtractor greenhouse = mock(JobExtractor.class);
        when(greenhouse.supportedTypes()).thenReturn(Set.of(AtsType.GREENHOUSE));

        JobExtractor lever = mock(JobExtractor.class);
        when(lever.supportedTypes()).thenReturn(Set.of(AtsType.LEVER, AtsType.LEVER_EU));

        var registry = new JobExtractorRegistry(List.of(greenhouse, lever));

        assertThat(registry.getExtractor(AtsType.GREENHOUSE)).contains(greenhouse);
        assertThat(registry.getExtractor(AtsType.LEVER)).contains(lever);
        assertThat(registry.getExtractor(AtsType.LEVER_EU)).contains(lever);
    }

    @Test
    void getExtractor_unregisteredType_returnsEmpty() {
        JobExtractor greenhouse = mock(JobExtractor.class);
        when(greenhouse.supportedTypes()).thenReturn(Set.of(AtsType.GREENHOUSE));

        var registry = new JobExtractorRegistry(List.of(greenhouse));

        assertThat(registry.getExtractor(AtsType.ASHBY)).isEmpty();
        assertThat(registry.getExtractor(AtsType.WORKDAY)).isEmpty();
    }

    @Test
    void supportedTypes_returnsAllRegistered() {
        JobExtractor greenhouse = mock(JobExtractor.class);
        when(greenhouse.supportedTypes()).thenReturn(Set.of(AtsType.GREENHOUSE));

        JobExtractor ashby = mock(JobExtractor.class);
        when(ashby.supportedTypes()).thenReturn(Set.of(AtsType.ASHBY));

        var registry = new JobExtractorRegistry(List.of(greenhouse, ashby));

        assertThat(registry.supportedTypes()).containsExactlyInAnyOrder(AtsType.GREENHOUSE, AtsType.ASHBY);
    }

    @Test
    void emptyExtractorList_noTypes() {
        var registry = new JobExtractorRegistry(List.of());
        assertThat(registry.supportedTypes()).isEmpty();
        assertThat(registry.getExtractor(AtsType.GREENHOUSE)).isEmpty();
    }
}
