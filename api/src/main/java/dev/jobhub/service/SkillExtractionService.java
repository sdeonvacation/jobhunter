package dev.jobhub.service;

import dev.jobhub.ai.AiProvider;
import dev.jobhub.ai.ExtractedSkill;
import dev.jobhub.ai.SkillExtractionResponse;
import dev.jobhub.ai.SkillTaxonomy;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.JobSkill;
import dev.jobhub.model.enums.SkillCategory;
import dev.jobhub.repository.JobSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Extracts skills from job descriptions using AI and persists to JobSkill table.
 */
@Slf4j
@Service
public class SkillExtractionService {

    private static final String EXTRACTION_PROMPT = """
            Extract all technical and professional skills mentioned in this job posting.
            For each skill, determine:
            - name: the skill/technology name
            - category: one of LANGUAGE, FRAMEWORK, DATABASE, CLOUD, TOOL, METHODOLOGY, SOFT_SKILL
            - required: true if explicitly required/mandatory, false if nice-to-have/preferred
            - rawMention: the exact phrase from the text where this skill was mentioned
            
            Be thorough but precise. Only extract actual skills, not general responsibilities.
            """;

    private final AiProvider aiProvider;
    private final JobSkillRepository jobSkillRepository;

    public SkillExtractionService(AiProvider aiProvider, JobSkillRepository jobSkillRepository) {
        this.aiProvider = aiProvider;
        this.jobSkillRepository = jobSkillRepository;
    }

    /**
     * Extract skills for a job posting if not already extracted.
     * Returns true if extraction was performed, false if skipped.
     */
    @Transactional
    public boolean extractSkills(JobPosting job) {
        List<JobSkill> existing = jobSkillRepository.findByJobId(job.getId());
        if (!existing.isEmpty()) {
            log.debug("Skills already extracted for job {}, skipping", job.getId());
            return false;
        }

        if (!aiProvider.isAvailable()) {
            log.warn("AI provider not available, cannot extract skills for job {}", job.getId());
            return false;
        }

        String content = buildContent(job);
        if (content.isBlank()) {
            log.debug("No description available for job {}, skipping extraction", job.getId());
            return false;
        }

        try {
            SkillExtractionResponse response = extractWithRetry(content);

            List<JobSkill> skills = mapToEntities(job, response);
            jobSkillRepository.saveAll(skills);
            log.info("Extracted {} skills for job {} ({})", skills.size(), job.getId(), job.getTitle());
            return true;
        } catch (Exception e) {
            log.error("Skill extraction failed for job {} after retries: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Batch extract skills for multiple jobs.
     */
    @Transactional
    public int extractSkillsBatch(List<JobPosting> jobs) {
        int extracted = 0;
        for (JobPosting job : jobs) {
            if (extractSkills(job)) {
                extracted++;
            }
        }
        return extracted;
    }

    private SkillExtractionResponse extractWithRetry(String content) {
        try {
            return aiProvider.extract(EXTRACTION_PROMPT, content, SkillExtractionResponse.class);
        } catch (Exception firstAttempt) {
            log.warn("AI extraction failed, retrying in 2s: {}", firstAttempt.getMessage());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw firstAttempt;
            }
            return aiProvider.extract(EXTRACTION_PROMPT, content, SkillExtractionResponse.class);
        }
    }

    private String buildContent(JobPosting job) {
        StringBuilder sb = new StringBuilder();
        if (job.getTitle() != null) {
            sb.append("Title: ").append(job.getTitle()).append("\n\n");
        }
        if (job.getDescription() != null) {
            sb.append(job.getDescription());
        }
        return sb.toString().trim();
    }

    private List<JobSkill> mapToEntities(JobPosting job, SkillExtractionResponse response) {
        if (response == null || response.skills() == null) {
            return List.of();
        }

        List<JobSkill> skills = new ArrayList<>();
        for (ExtractedSkill extracted : response.skills()) {
            String normalized = SkillTaxonomy.normalize(extracted.name());
            SkillCategory category = parseCategory(extracted.category());

            JobSkill skill = JobSkill.builder()
                    .id(UUID.randomUUID())
                    .job(job)
                    .skillName(normalized)
                    .rawMention(extracted.rawMention())
                    .category(category)
                    .isRequired(extracted.required())
                    .build();
            skills.add(skill);
        }
        return skills;
    }

    private SkillCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return SkillCategory.TOOL;
        }
        try {
            return SkillCategory.valueOf(category.toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return SkillCategory.TOOL;
        }
    }
}
