package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoeFilterConfigTest {

    @Test
    void usesConfiguredMaxYears() {
        PersonalProfileLoader loader = loaderWithYoeConfig(3);
        YoeFilter filter = new YoeFilterImpl(loader);

        assertThat(filter.filter(3).decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter(4).decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultMaxYearsWhenConfigNull() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        YoeFilter filter = new YoeFilterImpl(loader);

        // Default is 5
        assertThat(filter.filter(5).decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter(6).decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultWhenYoeConfigNull() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(null, null, null, null, null),
                null, null, null));
        YoeFilter filter = new YoeFilterImpl(loader);

        assertThat(filter.filter(5).decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter(6).decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void extractYoe_standardPatterns() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        YoeFilter filter = new YoeFilterImpl(loader);

        assertThat(filter.extractYoe("5+ years of experience")).isEqualTo(5);
        assertThat(filter.extractYoe("3 years experience in Java")).isEqualTo(3);
        assertThat(filter.extractYoe(null)).isNull();
        assertThat(filter.extractYoe("")).isNull();
        assertThat(filter.extractYoe("no yoe mentioned")).isNull();
    }

    @Test
    void extractYoe_standaloneWithPunctuation() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        YoeFilter filter = new YoeFilterImpl(loader);

        // CRX-style: "typically 8+ years, but we care more about depth"
        assertThat(filter.extractYoe("typically 8+ years, but we care more about depth")).isEqualTo(8);
        assertThat(filter.extractYoe("Deep production experience (typically 8+ years, but...)")).isEqualTo(8);
        assertThat(filter.extractYoe("6+ years.")).isEqualTo(6);
    }

    @Test
    void extractYoe_returnsMaxNotFirst() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        YoeFilter filter = new YoeFilterImpl(loader);

        // Should return max (8), not the first match (2)
        assertThat(filter.extractYoe("2+ years of experience. Ideally 8+ years, but open to strong engineers.")).isEqualTo(8);
    }

    @Test
    void filterNull_keeps() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        YoeFilter filter = new YoeFilterImpl(loader);

        assertThat(filter.filter(null).decision()).isEqualTo(FilterDecision.KEEP);
    }

    private PersonalProfileLoader loaderWithYoeConfig(int maxYears) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(null, null,
                        new PersonalProfile.YoeFilterConfig(maxYears), null, null),
                null, null, null));
        return loader;
    }

    private PersonalProfileLoader loaderWithNullFilters() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        return loader;
    }
}
