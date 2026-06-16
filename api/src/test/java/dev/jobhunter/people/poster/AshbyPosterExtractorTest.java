package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AshbyPosterExtractorTest {

    private AshbyPosterExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new AshbyPosterExtractor();
    }

    @Test
    void supports_ashbyOnly() {
        assertThat(extractor.supports(AtsType.ASHBY)).isTrue();
        assertThat(extractor.supports(AtsType.LEVER)).isFalse();
        assertThat(extractor.supports(AtsType.GREENHOUSE)).isFalse();
    }

    @Test
    void extract_fromHiringTeamList() {
        Map<String, Object> json = Map.of(
                "hiringTeam", List.of(
                        Map.of("name", "Max Mueller", "title", "VP Engineering",
                                "linkedinUrl", "https://linkedin.com/in/maxmueller",
                                "photoUrl", "https://example.com/photo.jpg")
                )
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Max Mueller");
        assertThat(result.get().title()).isEqualTo("VP Engineering");
        assertThat(result.get().linkedinUrl()).isEqualTo("https://linkedin.com/in/maxmueller");
        assertThat(result.get().avatarUrl()).isEqualTo("https://example.com/photo.jpg");
    }

    @Test
    void extract_fromRecruiterField() {
        Map<String, Object> json = Map.of(
                "recruiter", Map.of("name", "Lisa Park", "title", "Talent Acquisition")
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Lisa Park");
        assertThat(result.get().title()).isEqualTo("Talent Acquisition");
    }

    @Test
    void extract_fromNestedJobPosting() {
        Map<String, Object> json = Map.of(
                "jobPosting", Map.of(
                        "hiringTeam", List.of(Map.of("name", "Nested Person"))
                )
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Nested Person");
    }

    @Test
    void extract_returnsEmpty_whenNullJson() {
        Optional<PosterInfo> result = extractor.extract(null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void extract_returnsEmpty_whenNoRelevantFields() {
        Map<String, Object> json = Map.of("title", "Software Engineer", "location", "Berlin");

        Optional<PosterInfo> result = extractor.extract(null, json);
        assertThat(result).isEmpty();
    }

    @Test
    void extract_skipsEmptyHiringTeam() {
        Map<String, Object> json = Map.of("hiringTeam", List.of());

        Optional<PosterInfo> result = extractor.extract(null, json);
        assertThat(result).isEmpty();
    }
}
