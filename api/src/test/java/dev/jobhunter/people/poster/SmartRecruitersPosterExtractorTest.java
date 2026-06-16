package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SmartRecruitersPosterExtractorTest {

    private SmartRecruitersPosterExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new SmartRecruitersPosterExtractor();
    }

    @Test
    void supports_smartRecruitersOnly() {
        assertThat(extractor.supports(AtsType.SMARTRECRUITERS)).isTrue();
        assertThat(extractor.supports(AtsType.GREENHOUSE)).isFalse();
        assertThat(extractor.supports(AtsType.LEVER)).isFalse();
    }

    @Test
    void extract_fromJsonCreator() {
        Map<String, Object> json = Map.of(
                "creator", Map.of(
                        "firstName", "Alice",
                        "lastName", "Brown",
                        "title", "Technical Recruiter",
                        "avatarUrl", "https://cdn.sr.com/avatar.jpg"
                )
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Alice Brown");
        assertThat(result.get().title()).isEqualTo("Technical Recruiter");
        assertThat(result.get().avatarUrl()).isEqualTo("https://cdn.sr.com/avatar.jpg");
    }

    @Test
    void extract_fromJsonCreatorWithName() {
        Map<String, Object> json = Map.of(
                "creator", Map.of("name", "Bob White")
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Bob White");
    }

    @Test
    void extract_returnsEmpty_whenNoDataFound() {
        Optional<PosterInfo> result = extractor.extract("<div>job content</div>", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extract_returnsEmpty_whenBothNull() {
        Optional<PosterInfo> result = extractor.extract(null, null);
        assertThat(result).isEmpty();
    }
}
