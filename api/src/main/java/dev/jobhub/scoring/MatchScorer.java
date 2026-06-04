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

    // Skill weights: heavy = core backend, low = frontend/secondary
    private static final Map<String, Double> SKILL_WEIGHTS = Map.ofEntries(
            // Heavy weight (core stack - Java/Spring is primary signal)
            Map.entry("java", 5.0),
            Map.entry("spring boot", 4.5),
            Map.entry("kotlin", 2.5),
            Map.entry("kubernetes", 3.0),
            Map.entry("docker", 2.5),
            Map.entry("aws", 2.5),
            Map.entry("kafka", 3.0),
            Map.entry("postgresql", 2.0),
            Map.entry("microservices", 2.0),
            Map.entry("terraform", 2.0),
            // Medium weight
            Map.entry("redis", 1.5),
            Map.entry("elasticsearch", 1.5),
            Map.entry("ci/cd", 1.5),
            Map.entry("sql", 1.5),
            Map.entry("rest apis", 1.0),
            Map.entry("hibernate/jpa", 1.5),
            Map.entry("gradle", 1.0),
            Map.entry("git", 0.5),
            Map.entry("junit", 1.5),
            Map.entry("solid", 1.5),
            Map.entry("distributed systems", 2.0),
            // Low weight (frontend/secondary)
            Map.entry("typescript", 0.3),
            Map.entry("node.js", 0.3),
            Map.entry("javascript", 0.3),
            Map.entry("go", 0.3),
            Map.entry("react", 0.3)
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
            double weight = SKILL_WEIGHTS.getOrDefault(skill.name().toLowerCase(), 1.0);
            totalWeight += weight;

            if (matchesSkill(textLower, skill.name())) {
                earnedScore += weight;
                matchedSkills.add(skill.name());
            } else {
                missingSkills.add(skill.name());
            }
        }

        // Also check for bonus signals not in profile
        if (textLower.contains("ai") || textLower.contains("machine learning") || textLower.contains("llm")) {
            earnedScore += 2.0;
            totalWeight += 2.0;
            matchedSkills.add("AI/ML");
        } else {
            totalWeight += 2.0;
        }

        // Score using a benchmark: a realistic "great match" job earns ~22 weight.
        // This avoids penalizing jobs for not mentioning every skill in the profile.
        double BENCHMARK_WEIGHT = 22.0;
        int overallScore = (int) Math.round((earnedScore / BENCHMARK_WEIGHT) * 100);
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
            case "junit" -> text.contains("junit") || text.contains("testing framework") || text.contains("unit test");
            case "solid" -> text.contains("solid") || text.contains("design principle") || text.contains("clean code");
            case "distributed systems" -> text.contains("distributed") || text.contains("scalab") || text.contains("high availability");
            case "elasticsearch" -> text.contains("elasticsearch") || text.contains("elastic") || text.contains("opensearch");
            case "terraform" -> text.contains("terraform") || text.contains("infrastructure as code");
            case "node.js" -> text.contains("node.js") || text.contains("nodejs") || Pattern.compile("\\bnode\\b").matcher(text).find();
            case "javascript" -> text.contains("javascript") || Pattern.compile("\\bjs\\b").matcher(text).find();
            case "go" -> Pattern.compile("\\bgolang\\b|\\bgo\\b").matcher(text).find();
            case "react" -> Pattern.compile("\\breact\\b").matcher(text).find();
            case "ai/ml" -> text.contains("ai") || text.contains("machine learning") || text.contains("llm");
            default -> false;
        };
    }

    private Recommendation computeRecommendation(int score, int matchCount) {
        if (score >= 40 && matchCount >= 4) return Recommendation.APPLY;
        if (score >= 25 && matchCount >= 2) return Recommendation.MAYBE;
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
