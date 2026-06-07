package dev.jobhunter.scoring;

import dev.jobhunter.model.enums.Recommendation;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
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

    // Default skill weights (used when scoring config is absent)
    private static final Map<String, Double> DEFAULT_SKILL_WEIGHTS = Map.ofEntries(
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
            Map.entry("typescript", 0.3),
            Map.entry("node.js", 0.3),
            Map.entry("javascript", 0.3),
            Map.entry("go", 0.3),
            Map.entry("react", 0.3)
    );

    private static final double DEFAULT_BENCHMARK_WEIGHT = 22.0;
    private static final int DEFAULT_APPLY_SCORE = 40;
    private static final int DEFAULT_APPLY_MIN_MATCHES = 4;
    private static final int DEFAULT_MAYBE_SCORE = 25;
    private static final int DEFAULT_MAYBE_MIN_MATCHES = 2;
    private static final double DEFAULT_BONUS_WEIGHT = 2.0;
    private static final int DEFAULT_PRIMARY_SKILL_CAP = 70;
    private static final int DEFAULT_COMPETING_LANGUAGE_CAP = 50;
    private static final List<Pattern> DEFAULT_COMPETING_LANGUAGES = List.of();

    private final PersonalProfileLoader profileLoader;
    private final Map<String, Double> skillWeights;
    private final Map<String, List<Pattern>> compiledVariants;
    private final double benchmarkWeight;
    private final int applyScore;
    private final int applyMinMatches;
    private final int maybeScore;
    private final int maybeMinMatches;
    private final List<String> bonusSignals;
    private final double bonusWeight;
    private final List<String> primarySkills;
    private final int primarySkillCap;
    private final List<Pattern> competingLanguages;
    private final int competingLanguageCap;

    public MatchScorer(PersonalProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.ScoringConfig scoring = profile.scoring();

        if (scoring != null) {
            this.skillWeights = scoring.skillWeights().isEmpty()
                    ? DEFAULT_SKILL_WEIGHTS : scoring.skillWeights();
            this.benchmarkWeight = scoring.benchmarkWeight();
            this.bonusSignals = scoring.bonusSignals();
            this.bonusWeight = scoring.bonusWeight();

            if (scoring.thresholds() != null) {
                this.applyScore = scoring.thresholds().applyScore();
                this.applyMinMatches = scoring.thresholds().applyMinMatches();
                this.maybeScore = scoring.thresholds().maybeScore();
                this.maybeMinMatches = scoring.thresholds().maybeMinMatches();
            } else {
                this.applyScore = DEFAULT_APPLY_SCORE;
                this.applyMinMatches = DEFAULT_APPLY_MIN_MATCHES;
                this.maybeScore = DEFAULT_MAYBE_SCORE;
                this.maybeMinMatches = DEFAULT_MAYBE_MIN_MATCHES;
            }

            this.compiledVariants = compileVariants(scoring.skillVariants());
            this.primarySkills = scoring.primarySkills() != null ? scoring.primarySkills() : List.of();
            this.primarySkillCap = scoring.primarySkillCap() > 0
                    ? scoring.primarySkillCap() : DEFAULT_PRIMARY_SKILL_CAP;
            this.competingLanguages = compileCompetingLanguages(scoring.competingLanguages());
            this.competingLanguageCap = scoring.competingLanguageCap() > 0
                    ? scoring.competingLanguageCap() : DEFAULT_COMPETING_LANGUAGE_CAP;
        } else {
            this.skillWeights = DEFAULT_SKILL_WEIGHTS;
            this.benchmarkWeight = DEFAULT_BENCHMARK_WEIGHT;
            this.applyScore = DEFAULT_APPLY_SCORE;
            this.applyMinMatches = DEFAULT_APPLY_MIN_MATCHES;
            this.maybeScore = DEFAULT_MAYBE_SCORE;
            this.maybeMinMatches = DEFAULT_MAYBE_MIN_MATCHES;
            this.bonusSignals = List.of();
            this.bonusWeight = DEFAULT_BONUS_WEIGHT;
            this.compiledVariants = Map.of();
            this.primarySkills = List.of();
            this.primarySkillCap = DEFAULT_PRIMARY_SKILL_CAP;
            this.competingLanguages = DEFAULT_COMPETING_LANGUAGES;
            this.competingLanguageCap = DEFAULT_COMPETING_LANGUAGE_CAP;
        }
    }

    private Map<String, List<Pattern>> compileVariants(Map<String, List<String>> variantsConfig) {
        if (variantsConfig == null || variantsConfig.isEmpty()) return Map.of();

        Map<String, List<Pattern>> compiled = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : variantsConfig.entrySet()) {
            List<Pattern> patterns = new ArrayList<>();
            for (String regex : entry.getValue()) {
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            }
            compiled.put(entry.getKey(), patterns);
        }
        return compiled;
    }

    private List<Pattern> compileCompetingLanguages(List<String> languages) {
        if (languages == null || languages.isEmpty()) return List.of();
        List<Pattern> patterns = new ArrayList<>();
        for (String lang : languages) {
            patterns.add(Pattern.compile(lang, Pattern.CASE_INSENSITIVE));
        }
        return patterns;
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
            double weight = skillWeights.getOrDefault(skill.name().toLowerCase(), 1.0);
            totalWeight += weight;

            if (matchesSkill(textLower, skill.name())) {
                earnedScore += weight;
                matchedSkills.add(skill.name());
            } else {
                missingSkills.add(skill.name());
            }
        }

        // Check for bonus signals
        boolean bonusMatched = bonusSignals.stream().anyMatch(textLower::contains);
        if (bonusMatched) {
            earnedScore += bonusWeight;
            totalWeight += bonusWeight;
            matchedSkills.add("AI/ML");
        } else {
            totalWeight += bonusWeight;
        }

        int overallScore = (int) Math.round((earnedScore / benchmarkWeight) * 100);
        overallScore = Math.max(0, Math.min(100, overallScore));

        // Competing language penalty: if title explicitly targets another language, hard cap
        String titleLower = (title != null ? title : "").toLowerCase();
        boolean titleHasCompetingLanguage = competingLanguages.stream()
                .anyMatch(p -> p.matcher(titleLower).find());
        if (titleHasCompetingLanguage && overallScore > competingLanguageCap) {
            overallScore = competingLanguageCap;
        }

        // Primary language penalty: cap score if no core skill matched (softer than title penalty)
        boolean hasPrimarySkill = matchedSkills.stream()
                .anyMatch(s -> primarySkills.stream()
                        .anyMatch(ps -> ps.equalsIgnoreCase(s)));
        if (!hasPrimarySkill && overallScore > primarySkillCap) {
            overallScore = primarySkillCap;
        }

        Recommendation recommendation = computeRecommendation(overallScore, matchedSkills.size());

        return new MatchResult(overallScore, matchedSkills, missingSkills, recommendation);
    }

    /**
     * Check if a skill is mentioned in the text using configured variants.
     */
    private boolean matchesSkill(String text, String skillName) {
        String lower = skillName.toLowerCase();

        // Direct match (skill name appears in text)
        if (text.contains(lower)) {
            return true;
        }

        // Check configured variants
        List<Pattern> variants = compiledVariants.get(lower);
        if (variants != null) {
            for (Pattern pattern : variants) {
                if (pattern.matcher(text).find()) {
                    return true;
                }
            }
        }

        return false;
    }

    private Recommendation computeRecommendation(int score, int matchCount) {
        if (score >= applyScore && matchCount >= applyMinMatches) return Recommendation.APPLY;
        if (score >= maybeScore && matchCount >= maybeMinMatches) return Recommendation.MAYBE;
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
