package dev.jobhub.scoring;

import dev.jobhub.model.enums.Recommendation;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchScorerConfigTest {

    @Test
    void usesConfiguredSkillVariants() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 5.0, "kotlin", 3.0),
                Map.of("java", List.of("\\bjava\\b"), "kotlin", List.of("kotlin", "\\bkt\\b")),
                22.0, List.of("ai"), 2.0,
                new PersonalProfile.ScoringThresholds(40, 4, 25, 2)
        );
        MatchScorer scorer = new MatchScorer(loader);

        // "kt" should match kotlin via configured variant
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Senior KT Developer", "We need a kt developer with java experience");

        assertThat(result.matchedSkills()).contains("Kotlin");
        assertThat(result.matchedSkills()).contains("Java");
    }

    @Test
    void usesConfiguredThresholds() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 10.0),
                Map.of("java", List.of("\\bjava\\b")),
                10.0, List.of(), 0.0,
                new PersonalProfile.ScoringThresholds(80, 1, 50, 1)
        );
        MatchScorer scorer = new MatchScorer(loader);

        // Java weight=10, benchmark=10 → score=100, matches=1, threshold apply needs score>=80 and matches>=1
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Developer", "We need a java developer");

        assertThat(result.overallScore()).isEqualTo(100);
        assertThat(result.recommendation()).isEqualTo(Recommendation.APPLY);
    }

    @Test
    void usesConfiguredBenchmarkWeight() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 5.0),
                Map.of("java", List.of("\\bjava\\b")),
                50.0, List.of(), 0.0, // benchmark=50, so 5/50 = 10%
                new PersonalProfile.ScoringThresholds(40, 4, 25, 2)
        );
        MatchScorer scorer = new MatchScorer(loader);

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Developer", "We need a java developer");

        assertThat(result.overallScore()).isEqualTo(10); // 5/50 * 100
    }

    @Test
    void usesConfiguredBonusSignals() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 5.0),
                Map.of("java", List.of("\\bjava\\b")),
                10.0, List.of("blockchain", "web3"), 3.0,
                new PersonalProfile.ScoringThresholds(40, 1, 25, 1)
        );
        MatchScorer scorer = new MatchScorer(loader);

        // "blockchain" bonus signal present → adds 3.0 to earned
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Blockchain Dev", "java blockchain developer");

        // earned = 5 (java) + 3 (bonus) = 8, benchmark = 10 → 80%
        assertThat(result.overallScore()).isEqualTo(80);
        assertThat(result.matchedSkills()).contains("AI/ML"); // bonus label
    }

    @Test
    void bonusSignalNotMatched_noBonus() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 5.0),
                Map.of("java", List.of("\\bjava\\b")),
                10.0, List.of("blockchain"), 3.0,
                new PersonalProfile.ScoringThresholds(40, 1, 25, 1)
        );
        MatchScorer scorer = new MatchScorer(loader);

        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Developer", "java developer needed");

        // earned = 5 (java only), benchmark = 10 → 50%
        assertThat(result.overallScore()).isEqualTo(50);
        assertThat(result.matchedSkills()).doesNotContain("AI/ML");
    }

    @Test
    void fallsBackToDefaultsWhenScoringConfigNull() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "Test", "Dev", 3,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "language")),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null));
        MatchScorer scorer = new MatchScorer(loader);

        // Should still work with defaults — "java" direct match
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Developer", "We need a java developer");

        assertThat(result.matchedSkills()).contains("Java");
        assertThat(result.overallScore()).isGreaterThan(0);
    }

    @Test
    void emptyDescription_returnsZeroScore() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 5.0),
                Map.of("java", List.of("\\bjava\\b")),
                22.0, List.of(), 2.0,
                new PersonalProfile.ScoringThresholds(40, 4, 25, 2)
        );
        MatchScorer scorer = new MatchScorer(loader);

        MatchScorer.MatchResult result = scorer.scoreFromDescription(null, null);

        assertThat(result.overallScore()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo(Recommendation.SKIP);
    }

    @Test
    void maybeRecommendation_withConfiguredThresholds() {
        PersonalProfileLoader loader = loaderWithScoringConfig(
                Map.of("java", 5.0, "spring boot", 4.5),
                Map.of("java", List.of("\\bjava\\b"), "spring boot", List.of("spring")),
                20.0, List.of(), 0.0,
                new PersonalProfile.ScoringThresholds(50, 3, 20, 1)
        );
        MatchScorer scorer = new MatchScorer(loader);

        // java matches → earned=5, score=25%, matches=1 → maybe (score>=20, matches>=1)
        MatchScorer.MatchResult result = scorer.scoreFromDescription(
                "Java Developer", "java backend");

        assertThat(result.recommendation()).isEqualTo(Recommendation.MAYBE);
    }

    private PersonalProfileLoader loaderWithScoringConfig(
            Map<String, Double> skillWeights,
            Map<String, List<String>> skillVariants,
            double benchmarkWeight,
            List<String> bonusSignals,
            double bonusWeight,
            PersonalProfile.ScoringThresholds thresholds) {

        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        List<PersonalProfile.ProfileSkill> skills = skillWeights.keySet().stream()
                .map(name -> new PersonalProfile.ProfileSkill(
                        name.substring(0, 1).toUpperCase() + name.substring(1), "expert", "language"))
                .toList();

        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "Test", "Dev", 3, skills,
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null,
                new PersonalProfile.ScoringConfig(
                        benchmarkWeight, thresholds, bonusSignals, bonusWeight,
                        skillWeights, skillVariants, List.of(), 70, List.of(), 50),
                null
        ));
        return loader;
    }
}
