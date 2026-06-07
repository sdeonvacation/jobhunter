package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.CoverLetterDto;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.JobSkill;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.JobSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CoverLetterService {

    private static final String COVER_LETTER_SYSTEM_PROMPT = """
            You are a professional cover letter writer. Write a compelling, personalized cover letter
            for the given job posting using the candidate's profile. Rules:
            - Keep it concise (3-4 paragraphs).
            - Match the requested tone (professional, enthusiastic, conversational).
            - Highlight specific relevant experience and skills.
            - NEVER invent experience the candidate does not have.
            - Address the company by name and reference the role specifically.
            - Return only the letter text, no JSON wrapping.
            """;

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final JobSkillRepository jobSkillRepository;
    private final PersonalProfileLoader profileLoader;

    public CoverLetterService(AiProvider aiProvider,
                              JobPostingRepository jobPostingRepository,
                              JobSkillRepository jobSkillRepository,
                              PersonalProfileLoader profileLoader) {
        this.aiProvider = aiProvider;
        this.jobPostingRepository = jobPostingRepository;
        this.jobSkillRepository = jobSkillRepository;
        this.profileLoader = profileLoader;
    }

    public Optional<CoverLetterDto> generate(UUID jobId, String tone, String focus) {
        if (!aiProvider.isAvailable()) {
            log.warn("AI provider not available for cover letter generation");
            return Optional.empty();
        }

        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Cannot generate cover letter: job {} not found", jobId);
            return Optional.empty();
        }

        JobPosting job = jobOpt.get();
        List<JobSkill> skills = jobSkillRepository.findByJobId(jobId);
        PersonalProfile profile = profileLoader.getProfile();

        String userPrompt = buildCoverLetterPrompt(job, skills, profile, tone, focus);

        try {
            String letterContent = aiProvider.generate(COVER_LETTER_SYSTEM_PROMPT, userPrompt);

            CoverLetterDto result = new CoverLetterDto(
                    jobId,
                    job.getTitle(),
                    job.getCompany() != null ? job.getCompany().getName() : "Unknown",
                    letterContent,
                    tone != null ? tone : "professional"
            );

            log.info("Successfully generated cover letter for job {}", jobId);
            return Optional.of(result);
        } catch (Exception e) {
            log.error("Failed to generate cover letter for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    private String buildCoverLetterPrompt(JobPosting job, List<JobSkill> skills,
                                          PersonalProfile profile,
                                          String tone, String focus) {
        String skillsList = skills.stream()
                .filter(JobSkill::isRequired)
                .map(JobSkill::getSkillName)
                .collect(Collectors.joining(", "));

        String profileSkills = profile.skills().stream()
                .map(PersonalProfile.ProfileSkill::name)
                .collect(Collectors.joining(", "));

        String toneDirective = tone != null ? "Tone: " + tone : "Tone: professional";
        String focusDirective = focus != null && !focus.isBlank()
                ? "\nFocus on: " + focus
                : "";

        return """
                JOB: %s at %s
                LOCATION: %s
                DESCRIPTION: %s
                KEY SKILLS REQUIRED: %s
                
                CANDIDATE:
                Name: %s
                Title: %s
                Experience: %d years
                Skills: %s
                
                %s%s
                """.formatted(
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : "Unknown",
                job.getLocation() != null ? job.getLocation() : "Not specified",
                job.getDescription() != null ? job.getDescription().substring(0, Math.min(2000, job.getDescription().length())) : "",
                skillsList,
                profile.name(),
                profile.title(),
                profile.yearsOfExperience(),
                profileSkills,
                toneDirective,
                focusDirective
        );
    }
}
