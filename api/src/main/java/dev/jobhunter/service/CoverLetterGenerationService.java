package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.CoverLetter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.CoverLetterRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enhanced cover letter generation with persistence, versioning, and keyword mirroring.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverLetterGenerationService {

    private static final String KEYWORD_EXTRACTION_PROMPT = """
            Extract the top 8-12 technical keywords and key requirements from this job description.
            Return them as a JSON array of strings. Only include specific, concrete terms
            (technologies, methodologies, tools, domain terms). No generic words like "team" or "experience".
            """;

    private static final String COVER_LETTER_SYSTEM_PROMPT = """
            You are a direct, human-sounding cover letter writer for tech roles.
            Write like a thoughtful person who knows what they did. Prefer concrete mechanics,
            specific evidence, and clean sentences over polished corporate language.
            
            ## Structure
            - Hook: 1-3 sentences with one clear reason this role matters. No generic openers.
            - Value: strongest relevant experience for THIS role.
            - Evidence: 1-2 concrete examples connected to the job. One metric maximum.
            - Company fit: why this employer specifically, not generically.
            - Close: simple, confident, human. Single clear call-to-action.
            
            ## Format Rules
            - Length: 180-250 words MAX. Every sentence must earn its place.
            - Short paragraphs (2-3 sentences each). White space is your friend.
            - **Bold** the 3-5 most important keywords/technologies that match the JD.
            - Address the company by name. Reference the specific role.
            - Active voice only. Use contractions naturally.
            - Return only the letter text in markdown format. No JSON, no subject line.
            
            ## Banned Patterns (STRICT)
            - No em-dashes or semicolons.
            - No "not just X, but Y" constructions.
            - No "leveraging" or "utilizing" (use "using").
            - No "spearheaded" (use "led").
            - No "navigate," "foster," "delve," "landscape," "cutting-edge," "state-of-the-art," "synergy."
            - No "I am excited to apply..." or "I am passionate about..."
            - No "I bring a unique blend of..."
            - No "In today's..." openings.
            - No buzzwords: passionate, rockstar, ninja, thrive, dynamic.
            - No filler: "I am writing to express", "I believe I would be", "I would welcome the opportunity."
            - NEVER invent experience the candidate does not have.
            
            ## AI-Accent Checks (reject these patterns)
            - No label-colons like "Background:" or "Two proof points:" mid-prose.
            - No header phrases that announce the next paragraph instead of saying the thing.
            - No over-clipped punchy sentences in sequence (varies rhythm instead).
            - No colon after thesis statements.
            - No lists of three with identical rhythm or ascending importance.
            - No perfectly parallel sentence structures throughout.
            - No generic positive conclusions ("I look forward to contributing to your mission").
            - Vary sentence length. Mix short and medium. Avoid monotonous cadence.
            
            ## Counter-Pattern
            Write the way a person would write a thoughtful email to someone they respect.
            Slightly more context is better than suspiciously compressed prose.
            The result should feel defensible in an interview: "Yes, I wrote that."
            """;

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final CoverLetterRepository coverLetterRepository;
    private final PersonalProfileLoader profileLoader;

    /**
     * Generate a new cover letter version for the given job.
     */
    @Transactional
    public Optional<CoverLetter> generate(UUID jobId, String tone, String focus, List<String> angles) {
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
        if (job.getDescription() == null || job.getDescription().isBlank()) {
            log.warn("Cannot generate cover letter: job {} has no description", jobId);
            return Optional.empty();
        }

        String effectiveTone = tone != null && !tone.isBlank() ? tone : "professional";

        try {
            // Extract keywords from JD via AI
            List<String> keywords = extractKeywords(job.getDescription());

            // Build prompt combining JD keywords + profile + parameters
            PersonalProfile profile = profileLoader.getProfile();
            String userPrompt = buildPrompt(job, keywords, profile, effectiveTone, focus, angles);

            // Generate cover letter
            String content = aiProvider.generate(COVER_LETTER_SYSTEM_PROMPT, userPrompt);

            // Determine next version number
            int nextVersion = coverLetterRepository.countByJobId(jobId) + 1;

            CoverLetter coverLetter = CoverLetter.builder()
                    .job(job)
                    .content(content)
                    .tone(effectiveTone)
                    .focus(focus)
                    .angles(angles)
                    .keywordsMirrored(keywords)
                    .version(nextVersion)
                    .generatedAt(LocalDateTime.now())
                    .build();

            CoverLetter saved = coverLetterRepository.save(coverLetter);
            log.info("Generated cover letter v{} for job {} ({})",
                    nextVersion, jobId, job.getTitle());
            return Optional.of(saved);

        } catch (Exception e) {
            log.error("Failed to generate cover letter for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get the latest version cover letter for a job.
     */
    public Optional<CoverLetter> getForJob(UUID jobId) {
        return coverLetterRepository.findFirstByJobIdOrderByVersionDesc(jobId);
    }

    /**
     * Get all cover letter versions for a job, newest first.
     */
    public List<CoverLetter> list(UUID jobId) {
        return coverLetterRepository.findByJobIdOrderByVersionDesc(jobId);
    }

    /**
     * Update the content of an existing cover letter (manual edit).
     */
    @Transactional
    public Optional<CoverLetter> update(UUID coverId, String content) {
        Optional<CoverLetter> existing = coverLetterRepository.findById(coverId);
        if (existing.isEmpty()) {
            log.warn("Cover letter {} not found for update", coverId);
            return Optional.empty();
        }

        CoverLetter coverLetter = existing.get();
        coverLetter.setContent(content);
        coverLetter.setEditedAt(LocalDateTime.now());
        CoverLetter saved = coverLetterRepository.save(coverLetter);
        log.debug("Updated cover letter {}", coverId);
        return Optional.of(saved);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractKeywords(String description) {
        String truncated = description.length() > 3000
                ? description.substring(0, 3000) : description;
        try {
            List<String> keywords = aiProvider.extract(
                    KEYWORD_EXTRACTION_PROMPT, truncated, List.class);
            return keywords != null ? keywords : List.of();
        } catch (Exception e) {
            log.warn("Keyword extraction failed, proceeding without: {}", e.getMessage());
            return List.of();
        }
    }

    private static final java.util.Map<String, String> TONE_INSTRUCTIONS = java.util.Map.of(
            "professional", """
                    TONE: Professional. Clean, direct, confident. Write like a senior engineer's LinkedIn post. \
                    No slang, no exclamation marks. Let achievements speak. Understated confidence.""",
            "conversational", """
                    TONE: Conversational. Write like you're emailing a friend who works there. \
                    Relaxed but sharp. Contractions OK (I'm, I've, that's). \
                    Show personality. One touch of humor or self-awareness is fine. Still concise.""",
            "enthusiastic", """
                    TONE: Enthusiastic. High energy without being cringe. Show genuine excitement about \
                    the specific tech or mission (not generic "I love coding"). Use strong verbs. \
                    OK to use one exclamation mark. Still short and punchy, not rambling."""
    );

    private String buildPrompt(JobPosting job, List<String> keywords,
                               PersonalProfile profile, String tone,
                               String focus, List<String> angles) {
        // Group skills by proficiency for richer context
        String expertSkills = profile.skills().stream()
                .filter(s -> "expert".equalsIgnoreCase(s.proficiency()))
                .map(PersonalProfile.ProfileSkill::name)
                .collect(Collectors.joining(", "));
        String advancedSkills = profile.skills().stream()
                .filter(s -> "advanced".equalsIgnoreCase(s.proficiency()))
                .map(PersonalProfile.ProfileSkill::name)
                .collect(Collectors.joining(", "));
        String intermediateSkills = profile.skills().stream()
                .filter(s -> "intermediate".equalsIgnoreCase(s.proficiency()))
                .map(PersonalProfile.ProfileSkill::name)
                .collect(Collectors.joining(", "));

        String keywordsStr = String.join(", ", keywords);

        String anglesStr = angles != null && !angles.isEmpty()
                ? "\nAngles to emphasize: " + String.join(", ", angles)
                : "";

        String focusStr = focus != null && !focus.isBlank()
                ? "\nFocus on: " + focus
                : "";

        String toneInstruction = TONE_INSTRUCTIONS.getOrDefault(
                tone != null ? tone.toLowerCase() : "professional",
                TONE_INSTRUCTIONS.get("professional"));

        String preferencesStr = "";
        if (profile.preferences() != null) {
            var prefs = profile.preferences();
            preferencesStr = "\nPreferred locations: " + String.join(", ", prefs.locations())
                    + "\nSeniority: " + String.join(", ", prefs.seniority());
        }

        return """
                === JOB DESCRIPTION (use this to tailor the letter) ===
                Role: %s at %s
                Location: %s
                
                Full JD:
                %s
                
                Key JD keywords to bold/mirror: %s
                
                === CANDIDATE PROFILE (use this as source of truth for claims) ===
                Name: %s
                Title: %s
                Experience: %d years
                Expert in: %s
                Advanced in: %s
                Familiar with: %s%s
                
                IMPORTANT: Only claim skills/experience listed above. Match candidate strengths to JD requirements.
                Prioritize expert/advanced skills that overlap with JD keywords.
                
                %s%s%s
                """.formatted(
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : "Unknown",
                job.getLocation() != null ? job.getLocation() : "Not specified",
                job.getDescription().substring(0, Math.min(3000, job.getDescription().length())),
                keywordsStr,
                profile.name(),
                profile.title(),
                profile.yearsOfExperience(),
                expertSkills.isEmpty() ? "N/A" : expertSkills,
                advancedSkills.isEmpty() ? "N/A" : advancedSkills,
                intermediateSkills.isEmpty() ? "N/A" : intermediateSkills,
                preferencesStr,
                toneInstruction,
                focusStr,
                anglesStr
        );
    }
}
