package dev.jobhunter.source;

import dev.jobhunter.filter.FilterOverrides;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlSourceConfigTest {

    private YamlSourceConfig config(Map<String, Object> extraConfig) {
        return new YamlSourceConfig("test", JobSource.UNKNOWN, DiscoverySource.MANUAL, null, "https://example.com", 12, 50, false, extraConfig);
    }

    @Test
    @DisplayName("queries present: comma-separated values → keywords list")
    void queriesPresentParsed() {
        YamlSourceConfig cfg = config(Map.of("queries", "a,b,c"));

        FetchContext ctx = cfg.buildContext();

        assertThat(ctx.keywords()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("queries with whitespace: trimmed correctly")
    void queriesWhitespaceTrimmed() {
        YamlSourceConfig cfg = config(Map.of("queries", " a , b "));

        FetchContext ctx = cfg.buildContext();

        assertThat(ctx.keywords()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("queries absent in extraConfig → empty keywords list")
    void queriesAbsent() {
        YamlSourceConfig cfg = config(Map.of());

        FetchContext ctx = cfg.buildContext();

        assertThat(ctx.keywords()).isEmpty();
    }

    @Test
    @DisplayName("queries empty string → empty keywords list")
    void queriesEmptyString() {
        YamlSourceConfig cfg = config(Map.of("queries", ""));

        FetchContext ctx = cfg.buildContext();

        assertThat(ctx.keywords()).isEmpty();
    }

    @Test
    @DisplayName("extraConfig null → empty keywords list")
    void extraConfigNull() {
        YamlSourceConfig cfg = config(null);

        FetchContext ctx = cfg.buildContext();

        assertThat(ctx.keywords()).isEmpty();
    }

    @Test
    @DisplayName("filterOverrides returns the passed value")
    void filterOverridesReturnsPassedValue() {
        FilterOverrides overrides = new FilterOverrides(List.of("software"), List.of(), true);
        YamlSourceConfig cfg = new YamlSourceConfig("test", JobSource.UNKNOWN, DiscoverySource.MANUAL,
                null, "https://example.com", 12, 50, false, Map.of(), overrides);

        assertThat(cfg.filterOverrides()).isSameAs(overrides);
    }

    @Test
    @DisplayName("filterOverrides null → NONE")
    void filterOverridesNullReturnsNone() {
        YamlSourceConfig cfg = new YamlSourceConfig("test", JobSource.UNKNOWN, DiscoverySource.MANUAL,
                null, "https://example.com", 12, 50, false, Map.of(), null);

        assertThat(cfg.filterOverrides()).isEqualTo(FilterOverrides.NONE);
    }

    @Test
    @DisplayName("legacy constructor → NONE")
    void filterOverridesLegacyConstructorReturnsNone() {
        YamlSourceConfig cfg = config(Map.of());

        assertThat(cfg.filterOverrides()).isEqualTo(FilterOverrides.NONE);
    }

    @Test
    @DisplayName("translateTitles returns the passed value")
    void translateTitlesReturnsPassedValue() {
        YamlSourceConfig cfg = new YamlSourceConfig("test", JobSource.UNKNOWN, DiscoverySource.MANUAL,
                null, "https://example.com", 12, 50, false, Map.of(), FilterOverrides.NONE, true);

        assertThat(cfg.translateTitles()).isTrue();
    }

    @Test
    @DisplayName("legacy constructors default translateTitles to false")
    void translateTitlesDefaultsFalse() {
        YamlSourceConfig legacyNineArg = config(Map.of());
        assertThat(legacyNineArg.translateTitles()).isFalse();

        YamlSourceConfig legacyTenArg = new YamlSourceConfig("test", JobSource.UNKNOWN, DiscoverySource.MANUAL,
                null, "https://example.com", 12, 50, false, Map.of(), FilterOverrides.NONE);
        assertThat(legacyTenArg.translateTitles()).isFalse();
    }
}
