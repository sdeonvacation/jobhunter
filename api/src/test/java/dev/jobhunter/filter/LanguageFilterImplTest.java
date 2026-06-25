package dev.jobhunter.filter;

import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LanguageFilterImplTest {

    private static LanguageFilterImpl filter;

    @BeforeAll
    static void setUp() {
        // Full config with German detection and standard exclude patterns
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                new PersonalProfile.FilterConfig(
                        null, null, null,
                        new PersonalProfile.LanguageFilterConfig(
                                "en",
                                List.of("german"),
                                0.85,
                                List.of(
                                        "german\\s+c[12]",
                                        "deutsch\\s+c[12]",
                                        "flie[ßs]end\\s+deutsch",
                                        "fluent\\s+german",
                                        "muttersprache",
                                        "native\\s+german",
                                        "german\\s+native",
                                        "verhandlungssicher"
                                ),
                                List.of("nice\\s+to\\s+have", "preferred", "von\\s+vorteil",
                                        "\\bB[12]\\b", "basic\\s+german", "bonus", "optional",
                                        "ideal(ly)?", "advantage")
                        ), null),
                null, null, null));
        filter = new LanguageFilterImpl(loader);
    }

    @Test
    void englishDescription_noGermanRequirement_keep() {
        var result = filter.filter(
                "Backend Engineer",
                "We are looking for a backend engineer with experience in Java and Spring Boot. " +
                        "You will build microservices and work with distributed systems."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void germanDescription_skip() {
        var result = filter.filter(
                "Backend Entwickler",
                "Wir suchen einen erfahrenen Backend-Entwickler mit Java-Kenntnissen. " +
                        "Sie werden Microservices entwickeln und mit verteilten Systemen arbeiten. " +
                        "Gute Deutschkenntnisse sind erforderlich."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English JD (German)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "German C1 required for this role",
            "German C2 level is mandatory",
            "Deutsch C1 erforderlich",
            "fluent German is required",
            "fließend Deutsch required",
            "Muttersprache Deutsch or equivalent",
            "native German speaker required",
            "German native level communication",
            "verhandlungssicher in Deutsch"
    })
    void englishWithStrictGermanRequirement_skip(String requirement) {
        var result = filter.filter(
                "Software Engineer",
                "We are looking for a software engineer. Requirements: 5+ years Java. " + requirement
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-English language required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "German B1 is nice to have",
            "nice to have: German C1",
            "preferred: German C2",
            "German von Vorteil",
            "basic German is helpful"
    })
    void softGermanRequirement_keep(String softReq) {
        var result = filter.filter(
                "Software Engineer",
                "We are looking for a software engineer with Java skills. " + softReq +
                        ". Strong English communication required."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void nullDescription_keep() {
        var result = filter.filter("Engineer", null);
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void emptyDescription_keep() {
        var result = filter.filter("Engineer", "");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void nullTitle_handledGracefully() {
        var result = filter.filter(
                null,
                "We need a Java developer with strong Spring Boot experience and AWS knowledge."
        );
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void shortTextSkipsDetection_keep() {
        // Under 100 chars — Lingua detection not triggered
        var result = filter.filter("Dev", "Short text.");
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    void filterResult_keepFactory() {
        var result = FilterResult.keep();
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void filterResult_skipFactory() {
        var result = FilterResult.skip("some reason");
        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("some reason");
    }

    // --- stripNoise ---

    @Test
    void stripNoise_removesAshbyBracketUrl() {
        // Ashby descriptions embed image refs like [https://app.ashbyhq.com/.../dead.jpeg]
        // The hex UUIDs in those paths trigger false Dutch/German detections.
        String input = "[https://app.ashbyhq.com/api/images/user-content/1246dead-d30e-4c70-a1d3-ef7e5023d542/9ee053d4-9326-4e8b-89e5-15a98c57b52b/clash%20gold%20lovers.jpeg]\n\nWE ARE LOOKING FOR A SENIOR SOFTWARE ENGINEER TO JOIN OUR TEAM.";
        String result = LanguageFilterImpl.stripNoise(input);
        assertThat(result).doesNotContain("ashbyhq.com");
        assertThat(result).doesNotContain("dead");
        assertThat(result).contains("WE ARE LOOKING FOR A SENIOR SOFTWARE ENGINEER");
    }

    @Test
    void stripNoise_removesBareUrl() {
        String input = "Apply at https://jobs.example.com/apply/12345 before the deadline.";
        String result = LanguageFilterImpl.stripNoise(input);
        assertThat(result).doesNotContain("https://");
        assertThat(result).contains("Apply at");
        assertThat(result).contains("before the deadline");
    }

    @Test
    void stripNoise_removesMarkdownImageLink() {
        String input = "![Team photo](https://cdn.example.com/photo-dead-beef.jpg) Join our engineering team.";
        String result = LanguageFilterImpl.stripNoise(input);
        assertThat(result).doesNotContain("cdn.example.com");
        assertThat(result).contains("Join our engineering team");
    }

    @Test
    void stripNoise_preservesPlainText() {
        String input = "We are looking for a senior software engineer to join our team in Berlin.";
        assertThat(LanguageFilterImpl.stripNoise(input)).isEqualTo(input);
    }

    @Test
    void stripNoise_nullSafe() {
        assertThat(LanguageFilterImpl.stripNoise(null)).isNull();
        assertThat(LanguageFilterImpl.stripNoise("")).isEmpty();
    }

    @Test
    void ashbyDescription_withImageUrls_keepEnglishJob() {
        // Simulates a Supercell/Ashby job: image URL at top, English text below.
        // Before fix: Lingua detected "Dutch" from hex UUIDs → false SKIP.
        // After fix: URLs stripped → English detected → KEEP.
        String description = "[https://app.ashbyhq.com/api/images/user-content/1246dead-d30e-4c70-a1d3-ef7e5023d542/bad250f8-206a-41f4-9698-287303b69ce3/keyart%20supercell.png]\n\n"
                + "OUR ENGINE, TITAN, POWERS SOME OF THE HIGHEST-GROSSING MOBILE GAMES IN THE WORLD. "
                + "We are looking for a Senior Programmer to join our Engine team. "
                + "You will design and implement core engine systems, optimize rendering pipelines, "
                + "and work closely with game teams to ensure performance and stability across all Supercell titles. "
                + "We expect strong C++ skills and experience with mobile game development.";
        var result = filter.filter("Senior Programmer, Engine Reliability", description);
        assertThat(result.decision())
                .as("Ashby image URL should not trigger false non-English detection")
                .isEqualTo(FilterDecision.KEEP);
    }
}
