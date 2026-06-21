package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationFilterConfigTest {

    @Test
    void usesConfiguredCities() {
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of("amsterdam", "rotterdam"),
                List.of("^remote$")
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader);

        assertThat(filter.filter("Amsterdam").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Rotterdam, NL").decision()).isEqualTo(FilterDecision.KEEP);
        // Berlin not in custom list
        assertThat(filter.filter("Berlin").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void usesConfiguredRemotePatterns() {
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of("berlin"),
                List.of("^(remote|remote\\s*-\\s*(eu|global))$")
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader);

        assertThat(filter.filter("Remote").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Remote - EU").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Remote - US").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultsWhenConfigNull() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        LocationFilterImpl filter = new LocationFilterImpl(loader);

        // Default Germany cities should work
        assertThat(filter.filter("Berlin").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("München").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Remote - DACH").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("New York").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultsWhenLocationConfigNull() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(null, null, null, null, null),
                null, null, null));
        LocationFilterImpl filter = new LocationFilterImpl(loader);

        assertThat(filter.filter("Frankfurt").decision()).isEqualTo(FilterDecision.KEEP);
    }

    private PersonalProfileLoader loaderWithLocationConfig(List<String> cities, List<String> remotePatterns) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        null,
                        new PersonalProfile.LocationFilterConfig(cities, remotePatterns),
                        null,
                        null, null),
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
