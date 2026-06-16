package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LeverPosterExtractorTest {

    private LeverPosterExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new LeverPosterExtractor();
    }

    @Test
    void supports_leverAndLeverEu() {
        assertThat(extractor.supports(AtsType.LEVER)).isTrue();
        assertThat(extractor.supports(AtsType.LEVER_EU)).isTrue();
        assertThat(extractor.supports(AtsType.GREENHOUSE)).isFalse();
    }

    @Test
    void extract_fromJsonOwnerString() {
        Map<String, Object> json = Map.of("owner", "Sarah Johnson");

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Sarah Johnson");
    }

    @Test
    void extract_fromJsonOwnerMap() {
        Map<String, Object> json = Map.of("owner", Map.of("name", "Tom Williams"));

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Tom Williams");
    }

    @Test
    void extract_fromHtmlPostedBy() {
        String html = """
                <div class="posting-info">
                    <span>Posted by: Anna Schmidt</span>
                    <a href="https://linkedin.com/in/annaschmidt">Profile</a>
                </div>
                """;

        Optional<PosterInfo> result = extractor.extract(html, null);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Anna Schmidt");
        assertThat(result.get().linkedinUrl()).isEqualTo("https://linkedin.com/in/annaschmidt");
    }

    @Test
    void extract_returnsEmpty_whenNoDataFound() {
        Optional<PosterInfo> result = extractor.extract("<div>Nothing</div>", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extract_returnsEmpty_whenBothNull() {
        Optional<PosterInfo> result = extractor.extract(null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void extract_prefersJsonOverHtml() {
        Map<String, Object> json = Map.of("creator", "JSON Creator");
        String html = "<span>Posted by: HTML Person</span>";

        Optional<PosterInfo> result = extractor.extract(html, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("JSON Creator");
    }
}
