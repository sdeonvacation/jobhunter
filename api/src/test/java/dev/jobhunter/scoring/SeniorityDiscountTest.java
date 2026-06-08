package dev.jobhunter.scoring;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the configurable seniority discount applied to MatchScore.overallScore.
 */
class SeniorityDiscountTest {

    // Benchmark=10, java weight=10 → raw score=100 before any caps/discounts
    private static final Map<String, Double> SKILL_WEIGHTS = Map.of("java", 10.0);
    private static final Map<String, List<String>> SKILL_VARIANTS =
            Map.of("java", List.of("\\bjava\\b"));
    private static final double BENCHMARK = 10.0;
    private static final PersonalProfile.ScoringThresholds THRESHOLDS =
            new PersonalProfile.ScoringThresholds(40, 1, 25, 1);

    // -------------------------------------------------------------------------
    // Discount applied
    // -------------------------------------------------------------------------

    @Test
    void seniorTitleAppliesDiscount() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("senior"), 0.70));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Engineer", "java backend");

        // raw=100, after primary-skill cap (java matched, no cap applied), discount: round(100 * 0.70) = 70
        assertThat(result.overallScore()).isEqualTo(70);
    }

    @Test
    void staffTitleAppliesDiscount() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("senior", "staff"), 0.70));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Staff Java Engineer", "java backend");

        assertThat(result.overallScore()).isEqualTo(70);
    }

    @Test
    void leadTitleAppliesDiscount() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("lead"), 0.80));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Lead Java Developer", "java engineer");

        // round(100 * 0.80) = 80
        assertThat(result.overallScore()).isEqualTo(80);
    }

    @Test
    void keywordMatchIsCaseInsensitive() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("senior"), 0.70));

        // "SENIOR" in title should match lowercase keyword "senior"
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "SENIOR Java Engineer", "java backend");

        assertThat(result.overallScore()).isEqualTo(70);
    }

    @Test
    void keywordMatchedAsSubstring() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("senior"), 0.70));

        // "seniority" contains "senior" — substring match expected
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Engineer (seniority level III)", "java backend");

        assertThat(result.overallScore()).isEqualTo(70);
    }

    @Test
    void discountAppliedAfterPrimarySkillCap() {
        // primary skill cap: java NOT in primary-skills list → cap at 60
        // then seniority discount: round(60 * 0.70) = 42
        PersonalProfile.SeniorityDiscountConfig sd = discountConfig(true, List.of("senior"), 0.70);
        MatchScorer scorer = scorerWithPrimarySkillCap(sd, List.of(), 60);

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Developer", "java backend");

        assertThat(result.overallScore()).isEqualTo(42);
    }

    @Test
    void discountRoundsHalfUp() {
        // raw=100, multiplier=0.65 → 65.0, round → 65
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("senior"), 0.65));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Developer", "java backend");

        assertThat(result.overallScore()).isEqualTo(65);
    }

    // -------------------------------------------------------------------------
    // Discount NOT applied
    // -------------------------------------------------------------------------

    @Test
    void noMatchingKeyword_noDiscount() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of("senior", "staff"), 0.70));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Backend Developer", "java backend");

        assertThat(result.overallScore()).isEqualTo(100);
    }

    @Test
    void discountDisabled_keywordInTitle_noDiscount() {
        MatchScorer scorer = scorerWith(discountConfig(false, List.of("senior"), 0.70));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Developer", "java backend");

        assertThat(result.overallScore()).isEqualTo(100);
    }

    @Test
    void emptyKeywordList_noDiscount() {
        MatchScorer scorer = scorerWith(discountConfig(true, List.of(), 0.70));

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Developer", "java backend");

        assertThat(result.overallScore()).isEqualTo(100);
    }

    @Test
    void nullSeniorityDiscountConfig_noDiscount() {
        MatchScorer scorer = scorerWith(null);

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Developer", "java backend");

        assertThat(result.overallScore()).isEqualTo(100);
    }

    // -------------------------------------------------------------------------
    // Defaults when scoring config is absent
    // -------------------------------------------------------------------------

    @Test
    void nullScoringConfig_seniorityDiscountFieldsDefaultToDisabled() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "Test", "Dev", 3,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "language")),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        MatchScorer scorer = new MatchScorer(loader);

        // default MatchScorer: seniorityDiscountEnabled=false → no penalty
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior Java Developer", "java expert needed");

        // With default weights: java=5.0, benchmark=22.0 → 5/22*100 ≈ 23
        // No seniority discount applied
        assertThat(result.overallScore()).isGreaterThan(0);
        // Score should equal same call with non-senior title
        MatchScorer.MatchResult baseResult = scorer.scoreFromDescription(
                "Java Developer", "java expert needed");
        assertThat(result.overallScore()).isEqualTo(baseResult.overallScore());
    }

    // -------------------------------------------------------------------------
    // Loader parsing
    // -------------------------------------------------------------------------

    @Test
    void loadingFromYaml_seniorityDiscountParsed() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("sd-test");
        java.nio.file.Path file = tempDir.resolve("profile.yaml");
        java.nio.file.Files.writeString(file, """
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
                  benchmark-weight: 22.0
                  seniority-discount:
                    enabled: true
                    keywords: ["senior", "staff", "expert"]
                    multiplier: 0.70
                """);

        PersonalProfileLoader loader = new PersonalProfileLoader();
        org.springframework.test.util.ReflectionTestUtils.setField(
                loader, "profileResource",
                new org.springframework.core.io.FileSystemResource(file.toFile()));
        loader.load();

        PersonalProfile.SeniorityDiscountConfig sd = loader.getProfile().scoring().seniorityDiscount();
        assertThat(sd).isNotNull();
        assertThat(sd.enabled()).isTrue();
        assertThat(sd.keywords()).containsExactly("senior", "staff", "expert");
        assertThat(sd.multiplier()).isEqualTo(0.70);
    }

    @Test
    void loadingFromYaml_missingSeniorityDiscount_returnsNull() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("sd-test2");
        java.nio.file.Path file = tempDir.resolve("profile.yaml");
        java.nio.file.Files.writeString(file, """
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
                  benchmark-weight: 22.0
                """);

        PersonalProfileLoader loader = new PersonalProfileLoader();
        org.springframework.test.util.ReflectionTestUtils.setField(
                loader, "profileResource",
                new org.springframework.core.io.FileSystemResource(file.toFile()));
        loader.load();

        assertThat(loader.getProfile().scoring().seniorityDiscount()).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PersonalProfile.SeniorityDiscountConfig discountConfig(
            boolean enabled, List<String> keywords, double multiplier) {
        return new PersonalProfile.SeniorityDiscountConfig(enabled, keywords, multiplier);
    }

    private MatchScorer scorerWith(PersonalProfile.SeniorityDiscountConfig sd) {
        return scorerWithPrimarySkillCap(sd, List.of("java"), 70);
    }

    private MatchScorer scorerWithPrimarySkillCap(
            PersonalProfile.SeniorityDiscountConfig sd,
            List<String> primarySkills,
            int primarySkillCap) {

        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        List<PersonalProfile.ProfileSkill> skills = List.of(
                new PersonalProfile.ProfileSkill("Java", "expert", "language"));

        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "Test", "Dev", 3, skills,
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null,
                new PersonalProfile.ScoringConfig(
                        BENCHMARK, THRESHOLDS, List.of(), 0.0,
                        SKILL_WEIGHTS, SKILL_VARIANTS,
                        primarySkills, primarySkillCap,
                        List.of(), 50, sd),
                null, null));
        return new MatchScorer(loader);
    }
}
