package dev.jobhub.scoring;

import dev.jobhub.model.JobSkill;
import dev.jobhub.model.enums.Recommendation;
import dev.jobhub.model.enums.SkillCategory;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes match score (0-100) between job skills and personal profile.
 * Weighs skills by category importance and proficiency.
 */
@Slf4j
@Component
public class MatchScorer {

    // Category weights for scoring importance
    private static final Map<SkillCategory, Double> CATEGORY_WEIGHTS = Map.of(
            SkillCategory.LANGUAGE, 1.0,
            SkillCategory.FRAMEWORK, 0.9,
            SkillCategory.DATABASE, 0.8,
            SkillCategory.CLOUD, 0.7,
            SkillCategory.TOOL, 0.5,
            SkillCategory.METHODOLOGY, 0.4,
            SkillCategory.SOFT_SKILL, 0.3
    );

    // Proficiency multipliers
    private static final Map<String, Double> PROFICIENCY_SCORES = Map.of(
            "expert", 1.0,
            "advanced", 0.85,
            "intermediate", 0.65,
            "beginner", 0.4
    );

    private final PersonalProfileLoader profileLoader;

    public MatchScorer(PersonalProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    public MatchResult score(List<JobSkill> jobSkills) {
        if (jobSkills == null || jobSkills.isEmpty()) {
            return new MatchResult(50, List.of(), List.of(), Recommendation.MAYBE);
        }

        PersonalProfile profile = profileLoader.getProfile();
        Set<String> profileSkillNames = profile.skills().stream()
                .map(s -> s.name().toLowerCase())
                .collect(Collectors.toSet());

        Map<String, String> profileProficiency = profile.skills().stream()
                .collect(Collectors.toMap(
                        s -> s.name().toLowerCase(),
                        PersonalProfile.ProfileSkill::proficiency,
                        (a, b) -> a
                ));

        List<String> matchedSkills = new ArrayList<>();
        List<String> missingSkills = new ArrayList<>();
        double totalWeight = 0.0;
        double earnedScore = 0.0;

        for (JobSkill skill : jobSkills) {
            String skillLower = skill.getSkillName().toLowerCase();
            double categoryWeight = CATEGORY_WEIGHTS.getOrDefault(skill.getCategory(), 0.5);
            double requiredMultiplier = skill.isRequired() ? 1.5 : 1.0;
            double weight = categoryWeight * requiredMultiplier;
            totalWeight += weight;

            if (profileSkillNames.contains(skillLower)) {
                String proficiency = profileProficiency.getOrDefault(skillLower, "intermediate");
                double profScore = PROFICIENCY_SCORES.getOrDefault(proficiency, 0.65);
                earnedScore += weight * profScore;
                matchedSkills.add(skill.getSkillName());
            } else {
                missingSkills.add(skill.getSkillName());
            }
        }

        int overallScore = totalWeight > 0
                ? (int) Math.round((earnedScore / totalWeight) * 100)
                : 50;
        overallScore = Math.max(0, Math.min(100, overallScore));

        Recommendation recommendation = computeRecommendation(overallScore);

        return new MatchResult(overallScore, matchedSkills, missingSkills, recommendation);
    }

    private Recommendation computeRecommendation(int score) {
        if (score >= 70) return Recommendation.APPLY;
        if (score >= 50) return Recommendation.MAYBE;
        return Recommendation.SKIP;
    }

    public record MatchResult(
            int overallScore,
            List<String> matchedSkills,
            List<String> missingSkills,
            Recommendation recommendation
    ) {
    }
}
