package dev.jobhunter.filter;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LanguageFilterConfigTest {

    @Test
    void usesConfiguredExcludePatterns() {
        // Custom patterns: skip on "french C1" or "français requis"
        PersonalProfileLoader loader = loaderWithLanguageConfig("en", List.of(
                "french\\s+c[12]",
                "fran[çc]ais\\s+requis"
        ));
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.0, Language.ENGLISH, 1.0)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        // Custom pattern matches
        var result = filter.filter("Engineer", "We need an engineer. French C1 required.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);

        result = filter.filter("Engineer", "Français requis pour ce poste.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);

        // Default German patterns should NOT match (replaced by custom config)
        result = filter.filter("Engineer", "We need an engineer. German C1 required.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void fallsBackToDefaultPatternsWhenConfigNull() {
        PersonalProfileLoader loader = loaderWithNullFilters();
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.0, Language.ENGLISH, 1.0)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        // Default German patterns should work
        var result = filter.filter("Engineer", "We need an engineer. German C1 required for this role.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("German C1/C2 required");
    }

    @Test
    void fallsBackToDefaultPatternsWhenLanguageConfigNull() {
        PersonalProfileLoader loader = loaderWithFiltersButNoLanguage();
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.0, Language.ENGLISH, 1.0)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        var result = filter.filter("Engineer", "We need fluent German speakers for this role.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void fallsBackToDefaultPatternsWhenExcludePatternsEmpty() {
        PersonalProfileLoader loader = loaderWithLanguageConfig("en", List.of());
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.0, Language.ENGLISH, 1.0)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        var result = filter.filter("Engineer", "Muttersprache Deutsch required.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void softQualifierStillNegatesCustomPatterns() {
        PersonalProfileLoader loader = loaderWithLanguageConfig("en", List.of(
                "french\\s+c[12]"
        ));
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.0, Language.ENGLISH, 1.0)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        // Soft qualifier negates the match
        var result = filter.filter("Engineer", "nice to have: French C1 for communication.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void linguaDetectionStillWorksWithCustomPatterns() {
        PersonalProfileLoader loader = loaderWithLanguageConfig("en", List.of("french\\s+c[12]"));
        LanguageDetector detector = mock(LanguageDetector.class);
        // Simulate full German JD detection
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.90, Language.ENGLISH, 0.10)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        // Long enough text triggers Lingua detection
        String germanText = "Wir suchen einen erfahrenen Entwickler. ".repeat(5);
        var result = filter.filter("Entwickler", germanText);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("German JD");
    }

    private PersonalProfileLoader loaderWithLanguageConfig(String target, List<String> excludePatterns) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        null, null, null,
                        new PersonalProfile.LanguageFilterConfig(target, excludePatterns), null),
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

    private PersonalProfileLoader loaderWithFiltersButNoLanguage() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(null, null, null, null, null),
                null, null, null));
        return loader;
    }
}
