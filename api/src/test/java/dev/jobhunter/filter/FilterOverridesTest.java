package dev.jobhunter.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOverridesTest {

    @Test
    void none_isEmptyEmptyFalse() {
        assertThat(FilterOverrides.NONE.roleIncludePatterns()).isEmpty();
        assertThat(FilterOverrides.NONE.roleExcludeKeywords()).isEmpty();
        assertThat(FilterOverrides.NONE.languageExempt()).isFalse();
        assertThat(FilterOverrides.NONE.hasRoleOverride()).isFalse();
    }

    @Test
    void hasRoleOverride_trueWhenIncludeNonEmpty() {
        FilterOverrides overrides = new FilterOverrides(List.of("software"), List.of(), false);

        assertThat(overrides.hasRoleOverride()).isTrue();
    }

    @Test
    void hasRoleOverride_falseWhenIncludeEmptyButExcludePresent() {
        // An exclude-only override cannot replace the global include set, so it is not an override.
        FilterOverrides overrides = new FilterOverrides(List.of(), List.of("manager"), false);

        assertThat(overrides.hasRoleOverride()).isFalse();
    }

    @Test
    void hasRoleOverride_falseWhenIncludeNull() {
        FilterOverrides overrides = new FilterOverrides(null, List.of("manager"), false);

        assertThat(overrides.hasRoleOverride()).isFalse();
    }

    @Test
    void languageExempt_isCarriedIndependentlyOfRoleOverride() {
        FilterOverrides overrides = new FilterOverrides(List.of(), List.of(), true);

        assertThat(overrides.hasRoleOverride()).isFalse();
        assertThat(overrides.languageExempt()).isTrue();
    }
}
