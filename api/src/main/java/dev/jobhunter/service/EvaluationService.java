package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.EvaluationDto;
import dev.jobhunter.model.JobEvaluation;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.EvaluationArchetype;
import dev.jobhunter.model.enums.LegitimacyTier;
import dev.jobhunter.repository.JobEvaluationRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final JobEvaluationRepository jobEvaluationRepository;
    private final PersonalProfileLoader profileLoader;

    private static final List<String> ALL_BLOCKS = List.of(
            "roleSummary", "cvMatch", "levelStrategy", "compResearch",
            "customizationPlan", "interviewPlan", "legitimacy"
    );

    @Transactional
    public EvaluationDto evaluate(UUID jobId, List<String> blocks) {
        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));

        if (jobEvaluationRepository.existsByJobId(jobId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evaluation already exists for job: " + jobId);
        }

        if (job.getDescription() == null || job.getDescription().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Job has no description to evaluate");
        }

        List<String> targetBlocks = (blocks == null || blocks.isEmpty()) ? ALL_BLOCKS : blocks;
        PersonalProfile profile = profileLoader.getProfile();

        String context = buildContext(job, profile);

        Map<String, Object> roleSummary = null;
        Map<String, Object> cvMatch = null;
        Map<String, Object> levelStrategy = null;
        Map<String, Object> compResearch = null;
        Map<String, Object> customizationPlan = null;
        Map<String, Object> interviewPlan = null;
        Map<String, Object> legitimacy = null;

        for (String block : targetBlocks) {
            switch (block) {
                case "roleSummary" -> roleSummary = extractBlock(buildRoleSummaryPrompt(), context);
                case "cvMatch" -> cvMatch = extractBlock(buildCvMatchPrompt(profile), context);
                case "levelStrategy" -> levelStrategy = extractBlock(buildLevelStrategyPrompt(profile), context);
                case "compResearch" -> compResearch = extractBlock(buildCompResearchPrompt(), context);
                case "customizationPlan" -> customizationPlan = extractBlock(buildCustomizationPlanPrompt(profile), context);
                case "interviewPlan" -> interviewPlan = extractBlock(buildInterviewPlanPrompt(profile), context);
                case "legitimacy" -> legitimacy = extractBlock(buildLegitimacyPrompt(), context);
                default -> log.warn("Unknown evaluation block: {}", block);
            }
        }

        int overallScore = computeOverallScore(cvMatch, legitimacy, levelStrategy, compResearch, customizationPlan);
        EvaluationArchetype archetype = determineArchetype(overallScore, cvMatch, levelStrategy);
        LegitimacyTier tier = determineLegitimacyTier(legitimacy);

        JobEvaluation evaluation = JobEvaluation.builder()
                .id(UUID.randomUUID())
                .job(job)
                .roleSummary(roleSummary)
                .cvMatch(cvMatch)
                .levelStrategy(levelStrategy)
                .compResearch(compResearch)
                .customizationPlan(customizationPlan)
                .interviewPlan(interviewPlan)
                .legitimacy(legitimacy)
                .overallScore(overallScore)
                .archetype(archetype)
                .legitimacyTier(tier)
                .descriptionFingerprint(sha256(job.getDescription()))
                .evaluatedAt(LocalDateTime.now())
                .build();

        jobEvaluationRepository.save(evaluation);
        log.info("Evaluated job {} ({}): score={}, archetype={}, legitimacy={}",
                jobId, job.getTitle(), overallScore, archetype, tier);

        return toDto(evaluation, job);
    }

    public EvaluationDto getEvaluation(UUID jobId) {
        JobEvaluation evaluation = jobEvaluationRepository.findByJobId(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No evaluation for job: " + jobId));

        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));

        return toDto(evaluation, job);
    }

    @Transactional
    public void deleteEvaluation(UUID jobId) {
        jobEvaluationRepository.deleteByJobId(jobId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractBlock(String systemPrompt, String content) {
        try {
            // Use generate() instead of extract() — extract() with Map.class sends an empty
            // JSON schema causing Gemini to produce huge unstructured output that gets truncated.
            String response = aiProvider.generate(systemPrompt + "\n\nRespond ONLY with a valid JSON object. No markdown fences.", content);
            if (response == null || response.isBlank()) {
                return Map.of("error", "Empty AI response");
            }

            // Strip markdown fences if present
            String json = response.trim();
            if (json.startsWith("```")) {
                int first = json.indexOf('\n');
                if (first > 0) json = json.substring(first + 1);
                if (json.endsWith("```")) json = json.substring(0, json.length() - 3).trim();
            }

            // Find JSON object boundaries
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start) {
                return Map.of("error", "No JSON object in response");
            }
            json = json.substring(start, end + 1);

            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("AI extraction failed: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    int computeOverallScore(Map<String, Object> cvMatch, Map<String, Object> legitimacy,
                            Map<String, Object> levelStrategy, Map<String, Object> compResearch,
                            Map<String, Object> customizationPlan) {
        double cvFit = extractNumeric(cvMatch, "overallFit", 3);
        double legConf = extractNumeric(legitimacy, "confidence", 3);
        double levelFit = extractNumeric(levelStrategy, "fit", 3);
        double compAttr = extractNumeric(compResearch, "attractiveness", 3);
        double custEffort = extractNumeric(customizationPlan, "effortInverse", 3);

        double weighted = cvFit * 0.35 + legConf * 0.20 + levelFit * 0.20 + compAttr * 0.15 + custEffort * 0.10;
        return Math.max(1, Math.min(5, (int) Math.round(weighted)));
    }

    private double extractNumeric(Map<String, Object> block, String key, double defaultValue) {
        if (block == null) return defaultValue;
        Object val = block.get(key);
        if (val instanceof Number num) return num.doubleValue();
        return defaultValue;
    }

    EvaluationArchetype determineArchetype(int overallScore, Map<String, Object> cvMatch,
                                           Map<String, Object> levelStrategy) {
        double cvFit = extractNumeric(cvMatch, "overallFit", 3);
        double levelFit = extractNumeric(levelStrategy, "fit", 3);

        if (overallScore >= 4 && cvFit >= 4) return EvaluationArchetype.PERFECT_FIT;
        if (overallScore >= 3 && levelFit >= 4 && cvFit < 4) return EvaluationArchetype.GROWTH_STRETCH;
        if (overallScore >= 3 && cvFit >= 3 && levelFit <= 3) return EvaluationArchetype.LATERAL_MOVE;
        if (overallScore <= 2) return EvaluationArchetype.RED_FLAG;
        return EvaluationArchetype.LONG_SHOT;
    }

    LegitimacyTier determineLegitimacyTier(Map<String, Object> legitimacy) {
        double confidence = extractNumeric(legitimacy, "confidence", 3);
        if (confidence >= 4) return LegitimacyTier.GREEN;
        if (confidence >= 2.5) return LegitimacyTier.AMBER;
        return LegitimacyTier.RED;
    }

    private String buildContext(JobPosting job, PersonalProfile profile) {
        return String.format("""
                ## Job Details
                Title: %s
                Company: %s
                Location: %s
                
                ## Description
                %s
                
                ## Candidate Profile
                Name: %s
                Title: %s
                Years of Experience: %d
                Skills: %s
                """,
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : "Unknown",
                job.getLocation() != null ? job.getLocation() : "Not specified",
                job.getDescription(),
                profile.name(),
                profile.title(),
                profile.yearsOfExperience(),
                profile.skills().stream().map(s -> s.name() + " (" + s.proficiency() + ")").toList()
        );
    }

    private String buildRoleSummaryPrompt() {
        return """
                Analyze the job posting and return a JSON object with:
                - "title": the actual role title
                - "seniority": detected seniority level (junior/mid/senior/staff/lead/principal)
                - "teamSize": estimated team size if mentioned, null otherwise
                - "techStack": array of technologies mentioned
                - "responsibilities": array of key responsibilities (max 5)
                - "redFlags": array of any concerning signals
                - "greenFlags": array of positive signals
                Return ONLY valid JSON, no markdown.
                """;
    }

    private String buildCvMatchPrompt(PersonalProfile profile) {
        return String.format("""
                Compare the job requirements against the candidate's profile and return JSON:
                - "overallFit": score 1-5 (5 = perfect match)
                - "matchedSkills": array of skills the candidate has that match
                - "missingSkills": array of required skills the candidate lacks
                - "transferableSkills": array of candidate skills applicable but not directly listed
                - "experienceGap": description of any experience level mismatch
                - "strengthNarrative": 1-2 sentence pitch of why this candidate fits
                Candidate skills: %s
                Return ONLY valid JSON, no markdown.
                """, profile.skills().stream().map(s -> s.name()).toList());
    }

    private String buildLevelStrategyPrompt(PersonalProfile profile) {
        return String.format("""
                Evaluate seniority fit and return JSON:
                - "fit": score 1-5 (5 = exact level match)
                - "requiredYoe": years of experience required (integer or null)
                - "candidateYoe": %d
                - "seniorityMatch": "under" | "match" | "over"
                - "growthOpportunity": description of growth potential
                - "riskFactors": array of level-related concerns
                Return ONLY valid JSON, no markdown.
                """, profile.yearsOfExperience());
    }

    private String buildCompResearchPrompt() {
        return """
                Research signals about the company from the posting and return JSON:
                - "attractiveness": score 1-5 (5 = very attractive employer)
                - "companySize": estimated size if discernible (startup/scaleup/enterprise/unknown)
                - "culture": array of cultural signals detected
                - "benefits": array of benefits mentioned
                - "concerns": array of potential negatives
                - "industryFit": relevance of their industry to the candidate
                Return ONLY valid JSON, no markdown.
                """;
    }

    private String buildCustomizationPlanPrompt(PersonalProfile profile) {
        return String.format("""
                Plan how to customize application materials and return JSON:
                - "effortInverse": score 1-5 (5 = minimal customization needed, great baseline fit)
                - "keyThemes": array of themes to emphasize in resume/cover letter
                - "buzzwordsToInclude": array of keywords from the posting to mirror
                - "experienceToHighlight": array of which candidate experiences to foreground
                - "experienceToDeemphasize": array of what to minimize
                - "coverLetterAngle": 1-sentence suggested angle
                Candidate title: %s, skills: %s
                Return ONLY valid JSON, no markdown.
                """, profile.title(), profile.skills().stream().map(s -> s.name()).toList());
    }

    private String buildInterviewPlanPrompt(PersonalProfile profile) {
        return String.format("""
                Plan interview preparation and return JSON:
                - "likelyTopics": array of technical topics likely to come up
                - "behavioralThemes": array of behavioral question themes
                - "systemDesignTopics": array of possible system design topics
                - "questionsToAsk": array of smart questions the candidate should ask
                - "weakPoints": array of areas where candidate might struggle
                - "prepPriority": ordered array of what to study first
                Candidate background: %s with %d years experience.
                Return ONLY valid JSON, no markdown.
                """, profile.title(), profile.yearsOfExperience());
    }

    private String buildLegitimacyPrompt() {
        return """
                Assess posting legitimacy and return JSON:
                - "confidence": score 1-5 (5 = definitely legitimate posting)
                - "signals": array of legitimacy indicators found
                - "redFlags": array of suspicious elements
                - "postingAge": estimated freshness (fresh/recent/stale/unknown)
                - "salaryTransparency": boolean whether compensation is mentioned
                - "verdict": one of "legitimate" | "likely_legitimate" | "uncertain" | "suspicious" | "likely_fake"
                Return ONLY valid JSON, no markdown.
                """;
    }

    static String sha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private EvaluationDto toDto(JobEvaluation eval, JobPosting job) {
        return new EvaluationDto(
                job.getId(),
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : null,
                eval.getRoleSummary(),
                eval.getCvMatch(),
                eval.getLevelStrategy(),
                eval.getCompResearch(),
                eval.getCustomizationPlan(),
                eval.getInterviewPlan(),
                eval.getLegitimacy(),
                eval.getOverallScore(),
                eval.getArchetype(),
                eval.getLegitimacyTier(),
                eval.getDescriptionFingerprint(),
                eval.getEvaluatedAt()
        );
    }
}
