package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GreenhousePosterExtractorTest {

    private GreenhousePosterExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new GreenhousePosterExtractor();
    }

    @Test
    void supports_greenhouse() {
        assertThat(extractor.supports(AtsType.GREENHOUSE)).isTrue();
        assertThat(extractor.supports(AtsType.LEVER)).isFalse();
        assertThat(extractor.supports(AtsType.ASHBY)).isFalse();
    }

    @Test
    void extract_fromJsonHiringTeam() {
        Map<String, Object> json = Map.of(
                "hiring_team", List.of(
                        Map.of("name", "Jane Smith", "title", "Engineering Manager",
                                "linkedin_url", "https://linkedin.com/in/janesmith")
                )
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Jane Smith");
        assertThat(result.get().title()).isEqualTo("Engineering Manager");
        assertThat(result.get().linkedinUrl()).isEqualTo("https://linkedin.com/in/janesmith");
    }

    @Test
    void extract_fromJsonMetadata() {
        Map<String, Object> json = Map.of(
                "metadata", List.of(
                        Map.of("name", "Hiring Manager", "value", "John Doe")
                )
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("John Doe");
    }

    @Test
    void extract_fromHtml_nameNearRecruiter() {
        String html = """
                <div class="job-post">
                    <p>Contact: Recruiter Jane Miller for more info</p>
                    <a href="https://www.linkedin.com/in/janemiller">LinkedIn</a>
                </div>
                """;

        Optional<PosterInfo> result = extractor.extract(html, null);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Jane Miller");
        assertThat(result.get().linkedinUrl()).isEqualTo("https://www.linkedin.com/in/janemiller");
    }

    @Test
    void extract_returnsEmpty_whenNoDataFound() {
        String html = "<div>Just a regular job posting with no recruiter info</div>";
        Optional<PosterInfo> result = extractor.extract(html, Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extract_returnsEmpty_whenBothNull() {
        Optional<PosterInfo> result = extractor.extract(null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void extract_prefersJsonOverHtml() {
        Map<String, Object> json = Map.of(
                "hiring_team", List.of(Map.of("name", "JSON Person"))
        );
        String html = "<p>Recruiter HTML Person</p>";

        Optional<PosterInfo> result = extractor.extract(html, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("JSON Person");
    }

    @Test
    void extract_handlesEmptyHiringTeam() {
        Map<String, Object> json = Map.of("hiring_team", List.of());

        Optional<PosterInfo> result = extractor.extract(null, json);
        assertThat(result).isEmpty();
    }
}
