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
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("german"), 0.85, List.of(
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
        assertThat(result.reason()).isEqualTo("non-English language required");

        result = filter.filter("Engineer", "Français requis pour ce poste.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);

        // German C1 NOT in exclude patterns → not matched by pattern
        result = filter.filter("Engineer", "We need an engineer. German C1 required.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void noOpWhenConfigNull() {
        // Null filters → filter is a no-op (always KEEP)
        PersonalProfileLoader loader = loaderWithNullFilters();
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.95, Language.ENGLISH, 0.05)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        var result = filter.filter("Engineer", "We need an engineer. German C1 required for this role.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void noOpWhenLanguageConfigNull() {
        // Filters exist but language config is null → no-op
        PersonalProfileLoader loader = loaderWithFiltersButNoLanguage();
        LanguageDetector detector = mock(LanguageDetector.class);

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        var result = filter.filter("Engineer", "We need fluent German speakers for this role.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void noOpWhenExcludePatternsAndDetectLanguagesEmpty() {
        PersonalProfileLoader loader = loaderWithConfig("en", List.of(), 0.85, List.of());
        LanguageDetector detector = mock(LanguageDetector.class);

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        var result = filter.filter("Engineer", "Muttersprache Deutsch required. " +
                "Wir suchen einen erfahrenen Entwickler mit Java-Kenntnissen.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void softQualifierStillNegatesCustomPatterns() {
        PersonalProfileLoader loader = loaderWithConfig("en", null, 0.85, List.of(
                "french\\s+c[12]"
        ));
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.ENGLISH, 1.0)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        // Soft qualifier negates the match
        var result = filter.filter("Engineer", "nice to have: French C1 for communication.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void linguaDetectionSkipsNonEnglishWithConfiguredThreshold() {
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("german", "dutch"), 0.80, null);
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.90, Language.ENGLISH, 0.10)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        // Long enough text triggers Lingua detection
        String germanText = "Wir suchen einen erfahrenen Entwickler. ".repeat(5);
        var result = filter.filter("Entwickler", germanText);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English JD (German)");
    }

    @Test
    void linguaDetectionKeepsWhenBelowThreshold() {
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("german"), 0.85, null);
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.50, Language.ENGLISH, 0.50)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        String mixedText = "Some mixed content with some German words. ".repeat(5);
        var result = filter.filter("Engineer", mixedText);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void linguaDetectionReportsDutch() {
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("dutch"), 0.85, null);
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.DUTCH, 0.92, Language.ENGLISH, 0.08)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        String dutchText = "Wij zoeken een ervaren software-ontwikkelaar. ".repeat(5);
        var result = filter.filter("Developer", dutchText);
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English JD (Dutch)");
    }

    @Test
    void linguaDetectionHandlesExceptionGracefully() {
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("german"), 0.85, null);
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenThrow(new RuntimeException("detection failed"));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        String text = "Some long enough text for detection to be attempted by the filter. ".repeat(3);
        var result = filter.filter("Engineer", text);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void excludePatternCheckedBeforeLinguaDetection() {
        // Pattern match fires first, Lingua not called
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("german"), 0.85, List.of(
                "fluent\\s+german"
        ));
        LanguageDetector detector = mock(LanguageDetector.class);

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        var result = filter.filter("Engineer", "We need fluent German speakers. Strong Java skills required.");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English language required");

        // Lingua never called because pattern matched first
        verify(detector, never()).computeLanguageConfidenceValues(anyString());
    }

    @Test
    void customConfidenceThreshold() {
        // High threshold: 0.95 — only very confident detection triggers skip
        PersonalProfileLoader loader = loaderWithConfig("en", List.of("german"), 0.95, null);
        LanguageDetector detector = mock(LanguageDetector.class);
        when(detector.computeLanguageConfidenceValues(anyString()))
                .thenReturn(new TreeMap<>(Map.of(Language.GERMAN, 0.90, Language.ENGLISH, 0.10)));

        LanguageFilterImpl filter = new LanguageFilterImpl(loader, detector);

        String germanText = "Wir suchen einen erfahrenen Entwickler. ".repeat(5);
        var result = filter.filter("Dev", germanText);
        // 0.90 < 0.95 threshold → KEEP
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    private PersonalProfileLoader loaderWithConfig(String target, List<String> detectLanguages,
                                                   double confidenceThreshold, List<String> excludePatterns) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        null, null, null,
                        new PersonalProfile.LanguageFilterConfig(target, detectLanguages,
                                confidenceThreshold, excludePatterns,
                                List.of("nice\\s+to\\s+have", "preferred", "von\\s+vorteil",
                                        "\\bB[12]\\b", "basic\\s+german", "bonus", "optional",
                                        "ideal(ly)?", "advantage")), null),
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
