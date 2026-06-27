package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.InterviewPrepDto;
import dev.jobhunter.dto.InterviewStoryCreateDto;
import dev.jobhunter.dto.InterviewStoryDto;
import dev.jobhunter.model.InterviewPrep;
import dev.jobhunter.model.InterviewStory;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.InterviewPrepRepository;
import dev.jobhunter.repository.InterviewStoryRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPrepService {

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final InterviewPrepRepository interviewPrepRepository;
    private final InterviewStoryRepository interviewStoryRepository;

    /**
     * AI-generated interview prep: talking points mapped to JD, matched stories, company research.
     */
    @Transactional
    public Optional<InterviewPrepDto> prepareForJob(UUID jobId) {
        if (!aiProvider.isAvailable()) {
            log.warn("AI provider unavailable, cannot generate interview prep");
            return Optional.empty();
        }

        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Job not found for interview prep: {}", jobId);
            return Optional.empty();
        }

        JobPosting job = jobOpt.get();
        List<InterviewStory> stories = interviewStoryRepository.findAllByOrderByCreatedAtDesc();

        String storySummaries = stories.stream()
                .map(s -> String.format("ID: %s | Situation: %s | Action: %s | Skills: %s",
                        s.getId(), s.getSituation(), s.getAction(),
                        s.getSkills() != null ? String.join(", ", s.getSkills()) : "none"))
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                You are an interview preparation assistant. Given a job description and a bank of STAR stories,
                produce structured interview preparation material.
                
                Return JSON with:
                - "talkingPoints": array of objects with "requirement" (from JD), "point" (what to say),
                  "storyId" (UUID of matching story from bank, or null if none match)
                - "mappedStoryIds": array of UUIDs of stories that are relevant to this job
                - "companyResearch": object with "industry", "culture", "recentNews", "interviewTips" fields
                
                Be specific and actionable. Map real stories where they fit. Suggest angles even without stories.
                """;

        String content = String.format("""
                ## Job Description
                Title: %s
                Company: %s
                Location: %s
                
                %s
                
                ## STAR Story Bank
                %s
                """,
                job.getTitle(),
                job.getCompany().getName(),
                job.getLocation() != null ? job.getLocation() : "Not specified",
                job.getDescription() != null ? job.getDescription() : "No description available",
                storySummaries.isEmpty() ? "No stories in bank yet." : storySummaries);

        try {
            InterviewPrepAiResult aiResult = aiProvider.extract(systemPrompt, content, InterviewPrepAiResult.class);

            // Delete existing prep for this job if any
            interviewPrepRepository.findByJobId(jobId).ifPresent(existing -> {
                interviewPrepRepository.delete(existing);
                interviewPrepRepository.flush();
            });

            InterviewPrep prep = InterviewPrep.builder()
                    .job(job)
                    .talkingPoints(aiResult.talkingPoints())
                    .mappedStoryIds(aiResult.mappedStoryIds())
                    .companyResearch(aiResult.companyResearch())
                    .preparedAt(LocalDateTime.now())
                    .build();

            InterviewPrep saved = interviewPrepRepository.save(prep);
            log.info("Generated interview prep for job {} ({})", jobId, job.getTitle());

            return Optional.of(toDto(saved, job));
        } catch (Exception e) {
            log.error("Failed to generate interview prep for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<InterviewPrepDto> getPrep(UUID jobId) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }

        return interviewPrepRepository.findByJobId(jobId)
                .map(prep -> toDto(prep, jobOpt.get()));
    }

    @Transactional
    public InterviewStoryDto addStory(InterviewStoryCreateDto dto) {
        InterviewStory story = InterviewStory.builder()
                .situation(dto.situation())
                .task(dto.task())
                .action(dto.action())
                .result(dto.result())
                .reflection(dto.reflection())
                .tags(dto.tags())
                .skills(dto.skills())
                .sourceJobId(dto.sourceJobId())
                .build();

        InterviewStory saved = interviewStoryRepository.save(story);
        log.debug("Created interview story: {}", saved.getId());
        return toDto(saved);
    }

    public List<InterviewStoryDto> listStories() {
        return interviewStoryRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    public Optional<InterviewStoryDto> getStory(UUID id) {
        return interviewStoryRepository.findById(id).map(this::toDto);
    }

    @Transactional
    public Optional<InterviewStoryDto> updateStory(UUID id, InterviewStoryCreateDto dto) {
        Optional<InterviewStory> existing = interviewStoryRepository.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        InterviewStory story = existing.get();
        story.setSituation(dto.situation());
        story.setTask(dto.task());
        story.setAction(dto.action());
        story.setResult(dto.result());
        story.setReflection(dto.reflection());
        story.setTags(dto.tags());
        story.setSkills(dto.skills());
        story.setSourceJobId(dto.sourceJobId());

        InterviewStory saved = interviewStoryRepository.save(story);
        return Optional.of(toDto(saved));
    }

    @Transactional
    public boolean deleteStory(UUID id) {
        if (!interviewStoryRepository.existsById(id)) {
            return false;
        }
        interviewStoryRepository.deleteById(id);
        return true;
    }

    private InterviewPrepDto toDto(InterviewPrep prep, JobPosting job) {
        return new InterviewPrepDto(
                prep.getId(),
                job.getId(),
                job.getTitle(),
                job.getCompany().getName(),
                prep.getTalkingPoints(),
                prep.getMappedStoryIds(),
                prep.getCompanyResearch(),
                prep.getPreparedAt()
        );
    }

    private InterviewStoryDto toDto(InterviewStory story) {
        return new InterviewStoryDto(
                story.getId(),
                story.getSituation(),
                story.getTask(),
                story.getAction(),
                story.getResult(),
                story.getReflection(),
                story.getTags(),
                story.getSkills(),
                story.getSourceJobId(),
                story.getCreatedAt(),
                story.getUpdatedAt()
        );
    }

    /**
     * AI extraction target record for interview prep generation.
     */
    public record InterviewPrepAiResult(
            List<Map<String, Object>> talkingPoints,
            List<UUID> mappedStoryIds,
            Map<String, Object> companyResearch
    ) {}
}
