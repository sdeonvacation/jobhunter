package dev.jobhub.scoring;

import dev.jobhub.model.enums.Recommendation;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Computes match score (0-100) by regex-matching profile skills against job description.
 * No AI needed — simple keyword matching with proficiency weighting.
 */
@Slf4j
@Component
public class MatchScorer {

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

    /**
     * Score a job by matching profile skills against the description text.
     */
    public MatchResult scoreFromDescription(String title, String description) {
        String text = (title != null ? title : "") + " " + (description != null ? description : "");
        if (text.isBlank()) {
            return new MatchResult(0, List.of(), List.of(), Recommendation.SKIP);
        }

        String textLower = text.toLowerCase();
        PersonalProfile profile = profileLoader.getProfile();
        List<PersonalProfile.ProfileSkill> skills = profile.skills();

        List<String> matchedSkills = new ArrayList<>();
        List<String> missingSkills = new ArrayList<>();
        double totalWeight = 0.0;
        double earnedScore = 0.0;

        for (PersonalProfile.ProfileSkill skill : skills) {
            double weight = 1.0; // All profile skills weighted equally
            totalWeight += weight;

            if (matchesSkill(textLower, skill.name())) {
                double profScore = PROFICIENCY_SCORES.getOrDefault(skill.proficiency(), 0.65);
                earnedScore += weight * profScore;
                matchedSkills.add(skill.name());
            } else {
                missingSkills.add(skill.name());
            }
        }

        int overallScore = totalWeight > 0
                ? (int) Math.round((earnedScore / totalWeight) * 100)
                : 0;
        overallScore = Math.max(0, Math.min(100, overallScore));

        Recommendation recommendation = computeRecommendation(overallScore, matchedSkills.size());

        return new MatchResult(overallScore, matchedSkills, missingSkills, recommendation);
    }

    /**
     * Check if a skill is mentioned in the text. Handles variants.
     */
    private boolean matchesSkill(String text, String skillName) {
        String lower = skillName.toLowerCase();

        // Direct match
        if (text.contains(lower)) {
            return true;
        }

        // Handle common variants
        return switch (lower) {
            case "java" -> Pattern.compile("\\bjava\\b").matcher(text).find();
            case "spring boot" -> text.contains("spring") || text.contains("springboot");
            case "kotlin" -> text.contains("kotlin");
            case "postgresql" -> text.contains("postgres") || text.contains("postgresql");
            case "kafka" -> text.contains("kafka");
            case "kubernetes" -> text.contains("kubernetes") || text.contains("k8s");
            case "aws" -> Pattern.compile("\\baws\\b").matcher(text).find() || text.contains("amazon web");
            case "docker" -> text.contains("docker") || text.contains("container");
            case "typescript" -> text.contains("typescript") || text.contains("type script");
            case "microservices" -> text.contains("microservice") || text.contains("micro-service");
            case "rest apis" -> text.contains("rest") || text.contains("restful") || text.contains("api");
            case "ci/cd" -> text.contains("ci/cd") || text.contains("ci cd") || text.contains("continuous integration") || text.contains("continuous delivery");
            case "sql" -> Pattern.compile("\\bsql\\b").matcher(text).find();
            case "git" -> Pattern.compile("\\bgit\\b").matcher(text).find() || text.contains("github") || text.contains("gitlab");
            case "hibernate/jpa" -> text.contains("hibernate") || text.contains("jpa");
            case "gradle" -> text.contains("gradle") || text.contains("maven");
            case "redis" -> text.contains("redis");
            case "elasticsearch" -> text.contains("elasticsearch") || text.contains("elastic") || text.contains("opensearch");
            case "terraform" -> text.contains("terraform") || text.contains("infrastructure as code");
            default -> false;
        };
    }

    private Recommendation computeRecommendation(int score, int matchCount) {
        if (score >= 50 && matchCount >= 5) return Recommendation.APPLY;
        if (score >= 30 && matchCount >= 3) return Recommendation.MAYBE;
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
