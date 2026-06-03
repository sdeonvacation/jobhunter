package dev.jobhub.scoring;

import dev.jobhub.model.JobSkill;
import dev.jobhub.model.enums.Recommendation;
import dev.jobhub.model.enums.SkillCategory;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchScorerTest {

    @Mock
    private PersonalProfileLoader profileLoader;

    private MatchScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new MatchScorer(profileLoader);
        lenient().when(profileLoader.getProfile()).thenReturn(testProfile());
    }

    @Test
    void shouldReturnHighScoreForFullOverlap() {
        List<JobSkill> skills = List.of(
                skill("Java", SkillCategory.LANGUAGE, true),
                skill("Spring Boot", SkillCategory.FRAMEWORK, true),
                skill("PostgreSQL", SkillCategory.DATABASE, true),
                skill("AWS", SkillCategory.CLOUD, false)
        );

        MatchScorer.MatchResult result = scorer.score(skills);

        assertThat(result.overallScore()).isGreaterThanOrEqualTo(85);
        assertThat(result.recommendation()).isEqualTo(Recommendation.APPLY);
        assertThat(result.matchedSkills()).containsExactlyInAnyOrder("Java", "Spring Boot", "PostgreSQL", "AWS");
        assertThat(result.missingSkills()).isEmpty();
    }

    @Test
    void shouldReturnLowScoreForNoOverlap() {
        List<JobSkill> skills = List.of(
                skill("Scala", SkillCategory.LANGUAGE, true),
                skill("Akka", SkillCategory.FRAMEWORK, true),
                skill("Cassandra", SkillCategory.DATABASE, true)
        );

        MatchScorer.MatchResult result = scorer.score(skills);

        assertThat(result.overallScore()).isLessThan(50);
        assertThat(result.recommendation()).isEqualTo(Recommendation.SKIP);
        assertThat(result.matchedSkills()).isEmpty();
        assertThat(result.missingSkills()).containsExactlyInAnyOrder("Scala", "Akka", "Cassandra");
    }

    @Test
    void shouldReturnPartialScoreForMixedOverlap() {
        List<JobSkill> skills = List.of(
                skill("Java", SkillCategory.LANGUAGE, true),
                skill("Spring Boot", SkillCategory.FRAMEWORK, true),
                skill("Scala", SkillCategory.LANGUAGE, true),
                skill("Spark", SkillCategory.FRAMEWORK, false)
        );

        MatchScorer.MatchResult result = scorer.score(skills);

        assertThat(result.overallScore()).isBetween(40, 70);
        assertThat(result.matchedSkills()).contains("Java", "Spring Boot");
        assertThat(result.missingSkills()).contains("Scala", "Spark");
    }

    @Test
    void shouldWeightRequiredSkillsMoreHeavily() {
        // Required critical skill missing → lower score
        List<JobSkill> skillsMissing = List.of(
                skill("Scala", SkillCategory.LANGUAGE, true),  // required, missing
                skill("Docker", SkillCategory.TOOL, false)     // nice-to-have, have it
        );

        // Nice-to-have missing → higher score
        List<JobSkill> skillsPresent = List.of(
                skill("Java", SkillCategory.LANGUAGE, true),  // required, have it
                skill("Scala", SkillCategory.LANGUAGE, false) // nice-to-have, missing
        );

        MatchScorer.MatchResult missing = scorer.score(skillsMissing);
        MatchScorer.MatchResult present = scorer.score(skillsPresent);

        assertThat(present.overallScore()).isGreaterThan(missing.overallScore());
    }

    @Test
    void shouldReturnNeutralForEmptySkills() {
        MatchScorer.MatchResult result = scorer.score(List.of());
        assertThat(result.overallScore()).isEqualTo(50);
        assertThat(result.recommendation()).isEqualTo(Recommendation.MAYBE);
    }

    @Test
    void shouldReturnNeutralForNullSkills() {
        MatchScorer.MatchResult result = scorer.score(null);
        assertThat(result.overallScore()).isEqualTo(50);
        assertThat(result.recommendation()).isEqualTo(Recommendation.MAYBE);
    }

    @Test
    void shouldApplyProficiencyMultiplier() {
        // Expert in Java → higher score than intermediate in TypeScript
        List<JobSkill> javaOnly = List.of(skill("Java", SkillCategory.LANGUAGE, true));
        List<JobSkill> tsOnly = List.of(skill("TypeScript", SkillCategory.LANGUAGE, true));

        MatchScorer.MatchResult javaResult = scorer.score(javaOnly);
        MatchScorer.MatchResult tsResult = scorer.score(tsOnly);

        assertThat(javaResult.overallScore()).isGreaterThan(tsResult.overallScore());
    }

    @Test
    void shouldClassifyRecommendationByThreshold() {
        // Score >= 70 → APPLY
        List<JobSkill> highMatch = List.of(
                skill("Java", SkillCategory.LANGUAGE, true),
                skill("Spring Boot", SkillCategory.FRAMEWORK, true),
                skill("PostgreSQL", SkillCategory.DATABASE, true)
        );
        assertThat(scorer.score(highMatch).recommendation()).isEqualTo(Recommendation.APPLY);

        // Score < 50 → SKIP
        List<JobSkill> lowMatch = List.of(
                skill("Haskell", SkillCategory.LANGUAGE, true),
                skill("Elm", SkillCategory.FRAMEWORK, true)
        );
        assertThat(scorer.score(lowMatch).recommendation()).isEqualTo(Recommendation.SKIP);
    }

    private PersonalProfile testProfile() {
        return new PersonalProfile("Sam", "Senior Backend Engineer", 8,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "language"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "expert", "framework"),
                        new PersonalProfile.ProfileSkill("PostgreSQL", "expert", "database"),
                        new PersonalProfile.ProfileSkill("Kafka", "advanced", "messaging"),
                        new PersonalProfile.ProfileSkill("Kubernetes", "advanced", "infrastructure"),
                        new PersonalProfile.ProfileSkill("AWS", "advanced", "cloud"),
                        new PersonalProfile.ProfileSkill("Docker", "expert", "infrastructure"),
                        new PersonalProfile.ProfileSkill("TypeScript", "intermediate", "language"),
                        new PersonalProfile.ProfileSkill("Redis", "advanced", "database")
                ),
                new PersonalProfile.Preferences(
                        List.of("Germany", "Netherlands", "remote"),
                        "FULL_TIME", 85000,
                        List.of("senior", "staff", "lead"),
                        List.of("English", "German"),
                        List.of("gambling", "defense")
                )
        );
    }

    private JobSkill skill(String name, SkillCategory category, boolean required) {
        return JobSkill.builder()
                .id(UUID.randomUUID())
                .skillName(name)
                .category(category)
                .isRequired(required)
                .build();
    }
}
