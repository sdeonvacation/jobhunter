package dev.jobhub.service;

import dev.jobhub.ai.AiProvider;
import dev.jobhub.dto.TailoredResumeDto;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.JobSkill;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.repository.JobSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResumeTailoringService {

    private static final String TAILORING_SYSTEM_PROMPT = """
            You are a professional resume tailoring assistant. Given a candidate's profile and a job posting's
            requirements, produce a tailored resume summary and reordered experience bullet points that
            emphasize relevant skills. Rules:
            - NEVER invent experience or skills the candidate does not have.
            - Reorder and rephrase existing experience to highlight relevance.
            - Output JSON with keys: tailoredSummary (string), highlightedSkills (string[]), reorderedExperiencePoints (string[]).
            """;

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final JobSkillRepository jobSkillRepository;
    private final PersonalProfileLoader profileLoader;

    public ResumeTailoringService(AiProvider aiProvider,
                                  JobPostingRepository jobPostingRepository,
                                  JobSkillRepository jobSkillRepository,
                                  PersonalProfileLoader profileLoader) {
        this.aiProvider = aiProvider;
        this.jobPostingRepository = jobPostingRepository;
        this.jobSkillRepository = jobSkillRepository;
        this.profileLoader = profileLoader;
    }

    public Optional<TailoredResumeDto> tailor(UUID jobId, String emphasis, List<String> excludeSkills) {
        if (!aiProvider.isAvailable()) {
            log.warn("AI provider not available for resume tailoring");
            return Optional.empty();
        }

        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Cannot tailor resume: job {} not found", jobId);
            return Optional.empty();
        }

        JobPosting job = jobOpt.get();
        List<JobSkill> skills = jobSkillRepository.findByJobId(jobId);
        PersonalProfile profile = profileLoader.getProfile();

        String userPrompt = buildTailoringPrompt(job, skills, profile, emphasis, excludeSkills);

        try {
            TailoringResponse response = aiProvider.extract(
                    TAILORING_SYSTEM_PROMPT, userPrompt, TailoringResponse.class);

            // Validate: no invented skills
            List<String> validatedSkills = validateSkills(response.highlightedSkills(), profile);

            TailoredResumeDto result = new TailoredResumeDto(
                    jobId,
                    job.getTitle(),
                    job.getCompany() != null ? job.getCompany().getName() : "Unknown",
                    response.tailoredSummary(),
                    validatedSkills,
                    response.reorderedExperiencePoints() != null
                            ? response.reorderedExperiencePoints()
                            : List.of(),
                    emphasis
            );

            log.info("Successfully tailored resume for job {}", jobId);
            return Optional.of(result);
        } catch (Exception e) {
            log.error("Failed to tailor resume for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    private String buildTailoringPrompt(JobPosting job, List<JobSkill> skills,
                                        PersonalProfile profile,
                                        String emphasis, List<String> excludeSkills) {
        String skillsList = skills.stream()
                .map(s -> s.getSkillName() + " (" + s.getCategory() + ", required=" + s.isRequired() + ")")
                .collect(Collectors.joining("\n"));

        String profileSkills = profile.skills().stream()
                .map(s -> s.name() + " [" + s.proficiency() + "]")
                .collect(Collectors.joining(", "));

        String excludeClause = excludeSkills != null && !excludeSkills.isEmpty()
                ? "\nExclude these skills from emphasis: " + String.join(", ", excludeSkills)
                : "";

        String emphasisClause = emphasis != null && !emphasis.isBlank()
                ? "\nEmphasis: " + emphasis
                : "";

        return """
                JOB: %s at %s
                DESCRIPTION: %s
                
                REQUIRED SKILLS:
                %s
                
                CANDIDATE PROFILE:
                Name: %s
                Title: %s
                Experience: %d years
                Skills: %s
                %s%s
                """.formatted(
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : "Unknown",
                job.getDescription() != null ? job.getDescription().substring(0, Math.min(2000, job.getDescription().length())) : "",
                skillsList,
                profile.name(),
                profile.title(),
                profile.yearsOfExperience(),
                profileSkills,
                emphasisClause,
                excludeClause
        );
    }

    /**
     * Cross-check highlighted skills against profile to prevent hallucination.
     */
    private List<String> validateSkills(List<String> aiSuggested,
                                        PersonalProfile profile) {
        if (aiSuggested == null) return List.of();

        List<String> profileSkillNames = profile.skills().stream()
                .map(s -> s.name().toLowerCase())
                .toList();

        return aiSuggested.stream()
                .filter(skill -> profileSkillNames.contains(skill.toLowerCase()))
                .toList();
    }

    record TailoringResponse(
            String tailoredSummary,
            List<String> highlightedSkills,
            List<String> reorderedExperiencePoints
    ) {}
}
