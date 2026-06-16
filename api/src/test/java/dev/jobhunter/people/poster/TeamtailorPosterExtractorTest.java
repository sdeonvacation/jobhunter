package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TeamtailorPosterExtractorTest {

    private TeamtailorPosterExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TeamtailorPosterExtractor();
    }

    @Test
    void supports_teamtailorOnly() {
        assertThat(extractor.supports(AtsType.TEAMTAILOR)).isTrue();
        assertThat(extractor.supports(AtsType.GREENHOUSE)).isFalse();
    }

    @Test
    void extract_fromJsonRecruiterField() {
        Map<String, Object> json = Map.of(
                "recruiter", Map.of(
                        "name", "Erik Svensson",
                        "title", "People Partner",
                        "picture", "https://cdn.teamtailor.com/photo.jpg",
                        "linkedin", "https://linkedin.com/in/eriksvensson"
                )
        );

        Optional<PosterInfo> result = extractor.extract(null, json);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Erik Svensson");
        assertThat(result.get().title()).isEqualTo("People Partner");
        assertThat(result.get().avatarUrl()).isEqualTo("https://cdn.teamtailor.com/photo.jpg");
        assertThat(result.get().linkedinUrl()).isEqualTo("https://linkedin.com/in/eriksvensson");
    }

    @Test
    void extract_fromHtmlRecruiterSection() {
        String html = """
                <div class="recruiter-section">
                    <div class="recruiter" data-controller="recruiter">
                        <img src="https://cdn.example.com/avatar.jpg" alt="photo">
                        <h3>Maria Garcia</h3>
                        <span class="recruiter-title">Senior Recruiter</span>
                        <a href="https://linkedin.com/in/mariagarcia">Connect</a>
                    </div>
                </div>
                """;

        Optional<PosterInfo> result = extractor.extract(html, null);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Maria Garcia");
    }

    @Test
    void extract_returnsEmpty_whenNoRecruiterData() {
        String html = "<div class='job-posting'><h1>Software Engineer</h1></div>";
        Optional<PosterInfo> result = extractor.extract(html, Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extract_returnsEmpty_whenBothNull() {
        Optional<PosterInfo> result = extractor.extract(null, null);
        assertThat(result).isEmpty();
    }
}
