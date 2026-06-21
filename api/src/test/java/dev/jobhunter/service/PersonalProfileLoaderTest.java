package dev.jobhunter.service;

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
        assertThat(profile.filters().location().targetCities()).containsExactly("berlin", "munich");
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

    @Test
    void load_parsesLanguageFilterConfig() throws IOException {
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
                  language:
                    target: "en"
                    exclude-patterns:
                      - "german\\\\s+c[12]"
                      - "muttersprache"
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNotNull();
        assertThat(profile.filters().language()).isNotNull();
        assertThat(profile.filters().language().target()).isEqualTo("en");
        assertThat(profile.filters().language().excludePatterns()).containsExactly("german\\s+c[12]", "muttersprache");
    }

    @Test
    void load_targetCitiesKey_parsedCorrectly() throws IOException {
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
                  location:
                    target-cities:
                      - "amsterdam"
                      - "rotterdam"
                    remote-patterns:
                      - "^remote$"
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters().location()).isNotNull();
        assertThat(profile.filters().location().targetCities()).containsExactly("amsterdam", "rotterdam");
    }

    @Test
    void load_germanyCitiesKey_backwardCompat() throws IOException {
        // Old key "germany-cities" should still work and map to targetCities
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
                  location:
                    germany-cities:
                      - "berlin"
                      - "hamburg"
                    remote-patterns:
                      - "^remote$"
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters().location()).isNotNull();
        assertThat(profile.filters().location().targetCities()).containsExactly("berlin", "hamburg");
    }

    @Test
    void load_languageSectionAbsent_returnsNullLanguageConfig() throws IOException {
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
                    max-years: 5
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNotNull();
        assertThat(profile.filters().language()).isNull();
    }

    @Test
    void load_parsesVisaSponsorshipFilterConfig() throws IOException {
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
                  visa-sponsorship:
                    target-countries:
                      - "netherlands"
                      - "austria"
                    de-patterns:
                      - "\\\\bgermany\\\\b"
                    remote-eu-patterns:
                      - "remote.*europe"
                    positive-patterns:
                      - "visa\\\\s+sponsor"
                      - "relocation\\\\s+support"
                    negative-patterns:
                      - "no\\\\s+sponsor"
                    unknown-action: keep
                    ai-fallback:
                      enabled: true
                      max-description-chars: 3000
                      daily-limit: 25
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNotNull();
        assertThat(profile.filters().visaSponsorship()).isNotNull();

        var visa = profile.filters().visaSponsorship();
        assertThat(visa.targetCountries()).containsExactly("netherlands", "austria");
        assertThat(visa.dePatterns()).containsExactly("\\bgermany\\b");
        assertThat(visa.remoteEuPatterns()).containsExactly("remote.*europe");
        assertThat(visa.positivePatterns()).containsExactly("visa\\s+sponsor", "relocation\\s+support");
        assertThat(visa.negativePatterns()).containsExactly("no\\s+sponsor");
        assertThat(visa.unknownAction()).isEqualTo("keep");
        assertThat(visa.aiFallback()).isNotNull();
        assertThat(visa.aiFallback().enabled()).isTrue();
        assertThat(visa.aiFallback().maxDescriptionChars()).isEqualTo(3000);
        assertThat(visa.aiFallback().dailyLimit()).isEqualTo(25);
    }

    @Test
    void load_visaSponsorshipAbsent_returnsNull() throws IOException {
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
                    max-years: 5
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        assertThat(profile.filters()).isNotNull();
        assertThat(profile.filters().visaSponsorship()).isNull();
    }

    @Test
    void load_visaSponsorshipDefaults_whenFieldsMissing() throws IOException {
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
                  visa-sponsorship:
                    target-countries:
                      - "spain"
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        var visa = profile.filters().visaSponsorship();
        assertThat(visa).isNotNull();
        assertThat(visa.targetCountries()).containsExactly("spain");
        assertThat(visa.dePatterns()).isEmpty();
        assertThat(visa.remoteEuPatterns()).isEmpty();
        assertThat(visa.positivePatterns()).isEmpty();
        assertThat(visa.negativePatterns()).isEmpty();
        assertThat(visa.unknownAction()).isEqualTo("skip");
        assertThat(visa.aiFallback()).isNull();
    }

    @Test
    void load_visaSponsorshipAiFallbackDefaults() throws IOException {
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
                  visa-sponsorship:
                    target-countries: []
                    ai-fallback:
                      enabled: false
                """;

        PersonalProfileLoader loader = createLoader(yaml);
        PersonalProfile profile = loader.getProfile();

        var ai = profile.filters().visaSponsorship().aiFallback();
        assertThat(ai).isNotNull();
        assertThat(ai.enabled()).isFalse();
        assertThat(ai.maxDescriptionChars()).isEqualTo(4000);
        assertThat(ai.dailyLimit()).isEqualTo(50);
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
