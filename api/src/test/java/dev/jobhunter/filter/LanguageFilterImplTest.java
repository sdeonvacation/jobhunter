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
        // Null filters triggers default exclude patterns (backward compat)
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
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
        assertThat(result.reason()).isEqualTo("German JD");
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
        assertThat(result.reason()).isEqualTo("German C1/C2 required");
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
}
