package dev.jobhub.filter;

import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleRelevanceFilterConfigTest {

    @Test
    void usesConfiguredIncludePatterns() {
        PersonalProfileLoader loader = loaderWithRoleConfig(
                List.of("data\\s+scientist", "\\bai\\b"),
                List.of("intern")
        );
        RoleRelevanceFilterImpl filter = new RoleRelevanceFilterImpl(loader);

        assertThat(filter.filter("Senior Data Scientist").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("AI Researcher").decision()).isEqualTo(FilterDecision.KEEP);
        // "engineer" not in custom include list
        assertThat(filter.filter("Software Engineer").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void usesConfiguredExcludeKeywords() {
        PersonalProfileLoader loader = loaderWithRoleConfig(
                List.of("engineer", "developer"),
                List.of("intern", "junior")
        );
        RoleRelevanceFilterImpl filter = new RoleRelevanceFilterImpl(loader);

        assertThat(filter.filter("Junior Developer").decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(filter.filter("Engineering Intern").decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(filter.filter("Senior Engineer").decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void fallsBackToDefaultsWhenConfigNull() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        RoleRelevanceFilterImpl filter = new RoleRelevanceFilterImpl(loader);

        // Default patterns should work
        assertThat(filter.filter("Backend Engineer").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Engineering Manager").decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultsWhenRoleConfigNull() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(null, null, null),
                null, null));
        RoleRelevanceFilterImpl filter = new RoleRelevanceFilterImpl(loader);

        assertThat(filter.filter("Software Developer").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("Sales Manager").decision()).isEqualTo(FilterDecision.SKIP);
    }

    private PersonalProfileLoader loaderWithRoleConfig(List<String> include, List<String> exclude) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        new PersonalProfile.RoleFilterConfig(include, exclude),
                        null, null),
                null, null));
        return loader;
    }

    private PersonalProfileLoader loaderWithNullFilters() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null));
        return loader;
    }
}
