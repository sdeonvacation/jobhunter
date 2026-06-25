package dev.jobhunter.filter;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationFilterConfigTest {

    /** Creates a real CityCountryResolver with default target countries (DE/NL/AT/CH/IE/SE/DK/FI/ES). */
    private static CityCountryResolver defaultResolver() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(null);
        CityCountryResolver resolver = new CityCountryResolver(loader);
        ReflectionTestUtils.invokeMethod(resolver, "init");
        return resolver;
    }

    @Test
    void unknownActionKeep_allowsUnresolvedLocation() {
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of(),
                List.of(),
                "keep"
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        // Unknown location passes through when unknownAction=keep
        var result = filter.filter("Mars Colony");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.countryIso()).isNull();
    }

    @Test
    void unknownActionSkip_blocksUnresolvedLocation() {
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of(),
                List.of(),
                "skip"
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        // Unknown location is rejected when unknownAction=skip (default)
        var result = filter.filter("Mars Colony");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("location: not in target locations");
    }

    @Test
    void unknownActionDefault_blocksUnresolvedLocation() {
        // unknownAction=null → defaults to "skip"
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of(),
                List.of(),
                null
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        assertThat(filter.filter("Unknown Place").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void usesConfiguredRemotePatterns() {
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of("berlin"),
                List.of("^(remote|remote\\s*-\\s*(eu|global))$"),
                "skip"
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        assertThat(filter.filter("Remote").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Remote - EU").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Remote - US").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultsWhenConfigNull() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        // Default targets include DE — German cities should KEEP
        assertThat(filter.filter("Berlin").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Munich").decision()).isEqualTo(FilterDecision.KEEP);
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
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        assertThat(filter.filter("Berlin").decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void unknownActionKeep_knownNonTargetCountry_stillSkips() {
        PersonalProfileLoader loader = loaderWithLocationConfig(
                List.of(),
                List.of(),
                "keep"
        );
        LocationFilterImpl filter = new LocationFilterImpl(loader, defaultResolver());

        // unknownAction=keep only applies to UNRESOLVABLE locations.
        // Resolved non-target countries still get SKIP (using unambiguous country name).
        var result = filter.filter("United Kingdom");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).contains("GB");
    }

    private PersonalProfileLoader loaderWithLocationConfig(List<String> cities,
                                                           List<String> remotePatterns,
                                                           String unknownAction) {        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        null,
                        new PersonalProfile.LocationFilterConfig(remotePatterns, unknownAction),
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
