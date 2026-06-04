package dev.jobhub.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalProfileLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_parsesFilterConfig() throws IOException {
        String yaml = """
                name: Test
                title: Dev
                years-of-experience: 3
                skills: []
                preferences:
                  locations: []
                  employment-type: FULL_TIME
                  min-salary-eur: 0
                  seniority: []
                  languages: []
                  excluded-industries: []
                filters:
                  role:
                    include-patterns:
                      - "engineer"
                      - "\\\\bsre\\\\b"
                    exclude-keywords:
                      - "manager"
                      - "director"
                  location:
                    germany-cities:
                      - "berlin"
                      - "munich"
                    remote-patterns:
                      - "^remote$"
                  yoe:
                    max-years: 7
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNotNull();
        assertThat(profile.filters().role()).isNotNull();
        assertThat(profile.filters().role().includePatterns()).containsExactly("engineer", "\\bsre\\b");
        assertThat(profile.filters().role().excludeKeywords()).containsExactly("manager", "director");
        assertThat(profile.filters().location()).isNotNull();
        assertThat(profile.filters().location().germanyCities()).containsExactly("berlin", "munich");
        assertThat(profile.filters().location().remotePatterns()).containsExactly("^remote$");
        assertThat(profile.filters().yoe()).isNotNull();
        assertThat(profile.filters().yoe().maxYears()).isEqualTo(7);
    }

    @Test
    void load_parsesScoringConfig() throws IOException {
        String yaml = """
                name: Test
                title: Dev
                years-of-experience: 3
                skills: []
                preferences:
                  locations: []
                  employment-type: FULL_TIME
                  min-salary-eur: 0
                  seniority: []
                  languages: []
                  excluded-industries: []
                scoring:
                  benchmark-weight: 25.0
                  thresholds:
                    apply-score: 50
                    apply-min-matches: 5
                    maybe-score: 30
                    maybe-min-matches: 3
                  bonus-signals:
                    - "ai"
                    - "llm"
                  bonus-weight: 3.0
                  skill-weights:
                    java: 5.0
                    kotlin: 2.5
                  skill-variants:
                    java: ["\\\\bjava\\\\b"]
                    kotlin: ["kotlin", "kt"]
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.scoring()).isNotNull();
        assertThat(profile.scoring().benchmarkWeight()).isEqualTo(25.0);
        assertThat(profile.scoring().thresholds()).isNotNull();
        assertThat(profile.scoring().thresholds().applyScore()).isEqualTo(50);
        assertThat(profile.scoring().thresholds().applyMinMatches()).isEqualTo(5);
        assertThat(profile.scoring().thresholds().maybeScore()).isEqualTo(30);
        assertThat(profile.scoring().thresholds().maybeMinMatches()).isEqualTo(3);
        assertThat(profile.scoring().bonusSignals()).containsExactly("ai", "llm");
        assertThat(profile.scoring().bonusWeight()).isEqualTo(3.0);
        assertThat(profile.scoring().skillWeights()).containsEntry("java", 5.0);
        assertThat(profile.scoring().skillWeights()).containsEntry("kotlin", 2.5);
        assertThat(profile.scoring().skillVariants()).containsKey("java");
        assertThat(profile.scoring().skillVariants().get("java")).containsExactly("\\bjava\\b");
        assertThat(profile.scoring().skillVariants().get("kotlin")).containsExactly("kotlin", "kt");
    }

    @Test
    void load_missingFilterSection_returnsNull() throws IOException {
        String yaml = """
                name: Test
                title: Dev
                years-of-experience: 3
                skills: []
                preferences:
                  locations: []
                  employment-type: FULL_TIME
                  min-salary-eur: 0
                  seniority: []
                  languages: []
                  excluded-industries: []
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNull();
        assertThat(profile.scoring()).isNull();
    }

    @Test
    void load_partialFilterConfig_parsesAvailableSections() throws IOException {
        String yaml = """
                name: Test
                title: Dev
                years-of-experience: 3
                skills: []
                preferences:
                  locations: []
                  employment-type: FULL_TIME
                  min-salary-eur: 0
                  seniority: []
                  languages: []
                  excluded-industries: []
                filters:
                  yoe:
                    max-years: 10
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNotNull();
        assertThat(profile.filters().role()).isNull();
        assertThat(profile.filters().location()).isNull();
        assertThat(profile.filters().yoe()).isNotNull();
        assertThat(profile.filters().yoe().maxYears()).isEqualTo(10);
    }

    @Test
    void load_ioError_returnsEmptyProfile() {
        PersonalProfileLoader loader = new PersonalProfileLoader();
        ReflectionTestUtils.setField(loader, "profileResource",
                new FileSystemResource("/nonexistent/path/profile.yaml"));
        loader.load();

        PersonalProfile profile = loader.getProfile();
        assertThat(profile.name()).isEmpty();
        assertThat(profile.filters()).isNull();
        assertThat(profile.scoring()).isNull();
    }

    private PersonalProfileLoader createLoader(String yamlContent) throws IOException {
        Path file = tempDir.resolve("profile.yaml");
        Files.writeString(file, yamlContent);

        PersonalProfileLoader loader = new PersonalProfileLoader();
        ReflectionTestUtils.setField(loader, "profileResource", new FileSystemResource(file.toFile()));
        loader.load();
        return loader;
    }
}
