package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.InterviewPrepDto;
import dev.jobhunter.dto.InterviewStoryCreateDto;
import dev.jobhunter.dto.InterviewStoryDto;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.InterviewPrep;
import dev.jobhunter.model.InterviewStory;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.InterviewPrepRepository;
import dev.jobhunter.repository.InterviewStoryRepository;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterviewPrepServiceTest {

    @Mock private AiProvider aiProvider;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private InterviewPrepRepository interviewPrepRepository;
    @Mock private InterviewStoryRepository interviewStoryRepository;

    private InterviewPrepService service;

    @BeforeEach
    void setUp() {
        service = new InterviewPrepService(aiProvider, jobPostingRepository,
                interviewPrepRepository, interviewStoryRepository);
    }

    // --- prepareForJob tests ---

    @Test
    void prepareForJob_aiUnavailable_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(false);

        Optional<InterviewPrepDto> result = service.prepareForJob(UUID.randomUUID());

        assertThat(result).isEmpty();
        verifyNoInteractions(jobPostingRepository);
    }

    @Test
    void prepareForJob_jobNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<InterviewPrepDto> result = service.prepareForJob(jobId);

        assertThat(result).isEmpty();
    }

    @Test
    void prepareForJob_success_savesAndReturnsPrep() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp").build();
        JobPosting job = JobPosting.builder()
                .id(jobId).title("Backend Engineer").company(company)
                .description("Build microservices with Java and Spring Boot")
                .location("Berlin").build();

        InterviewStory story = InterviewStory.builder()
                .id(UUID.randomUUID()).situation("Led migration").action("Rewrote services")
                .skills(List.of("Java", "Spring Boot")).build();

        List<Map<String, Object>> talkingPoints = List.of(
                Map.of("requirement", "Java", "point", "5 years experience", "storyId", story.getId().toString())
        );
        List<UUID> mappedIds = List.of(story.getId());
        Map<String, Object> research = Map.of("industry", "SaaS", "culture", "Engineering-first");

        InterviewPrepService.InterviewPrepAiResult aiResult =
                new InterviewPrepService.InterviewPrepAiResult(talkingPoints, mappedIds, research);

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(interviewStoryRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(story));
        when(interviewPrepRepository.findByJobId(jobId)).thenReturn(Optional.empty());
        when(aiProvider.extract(anyString(), anyString(), eq(InterviewPrepService.InterviewPrepAiResult.class)))
                .thenReturn(aiResult);
        when(interviewPrepRepository.save(any(InterviewPrep.class))).thenAnswer(inv -> {
            InterviewPrep p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        Optional<InterviewPrepDto> result = service.prepareForJob(jobId);

        assertThat(result).isPresent();
        InterviewPrepDto dto = result.get();
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(dto.companyName()).isEqualTo("Acme Corp");
        assertThat(dto.talkingPoints()).hasSize(1);
        assertThat(dto.mappedStoryIds()).containsExactly(story.getId());
        assertThat(dto.companyResearch()).containsKey("industry");

        verify(interviewPrepRepository).save(any(InterviewPrep.class));
    }

    @Test
    void prepareForJob_existingPrepDeleted_beforeSavingNew() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Co").build();
        JobPosting job = JobPosting.builder()
                .id(jobId).title("Dev").company(company).description("desc").build();

        InterviewPrep existingPrep = InterviewPrep.builder()
                .id(UUID.randomUUID()).job(job).build();

        InterviewPrepService.InterviewPrepAiResult aiResult =
                new InterviewPrepService.InterviewPrepAiResult(List.of(), List.of(), Map.of());

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(interviewStoryRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(interviewPrepRepository.findByJobId(jobId)).thenReturn(Optional.of(existingPrep));
        when(aiProvider.extract(anyString(), anyString(), eq(InterviewPrepService.InterviewPrepAiResult.class)))
                .thenReturn(aiResult);
        when(interviewPrepRepository.save(any(InterviewPrep.class))).thenAnswer(inv -> {
            InterviewPrep p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        service.prepareForJob(jobId);

        verify(interviewPrepRepository).delete(existingPrep);
        verify(interviewPrepRepository).flush();
        verify(interviewPrepRepository).save(any(InterviewPrep.class));
    }

    @Test
    void prepareForJob_aiThrows_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("X").build();
        JobPosting job = JobPosting.builder()
                .id(jobId).title("Y").company(company).description("z").build();

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(interviewStoryRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(aiProvider.extract(anyString(), anyString(), eq(InterviewPrepService.InterviewPrepAiResult.class)))
                .thenThrow(new RuntimeException("AI timeout"));

        Optional<InterviewPrepDto> result = service.prepareForJob(jobId);

        assertThat(result).isEmpty();
        verify(interviewPrepRepository, never()).save(any());
    }

    // --- getPrep tests ---

    @Test
    void getPrep_jobNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<InterviewPrepDto> result = service.getPrep(jobId);

        assertThat(result).isEmpty();
    }

    @Test
    void getPrep_prepNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Co").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").company(company).build();

        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(interviewPrepRepository.findByJobId(jobId)).thenReturn(Optional.empty());

        Optional<InterviewPrepDto> result = service.getPrep(jobId);

        assertThat(result).isEmpty();
    }

    @Test
    void getPrep_found_returnsDto() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Corp").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Engineer").company(company).build();

        InterviewPrep prep = InterviewPrep.builder()
                .id(UUID.randomUUID()).job(job)
                .talkingPoints(List.of(Map.of("point", "test")))
                .mappedStoryIds(List.of())
                .companyResearch(Map.of("industry", "Tech"))
                .preparedAt(LocalDateTime.now())
                .build();

        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(interviewPrepRepository.findByJobId(jobId)).thenReturn(Optional.of(prep));

        Optional<InterviewPrepDto> result = service.getPrep(jobId);

        assertThat(result).isPresent();
        assertThat(result.get().jobTitle()).isEqualTo("Engineer");
        assertThat(result.get().companyName()).isEqualTo("Corp");
    }

    // --- addStory tests ---

    @Test
    void addStory_savesAndReturnsDto() {
        InterviewStoryCreateDto createDto = new InterviewStoryCreateDto(
                "Led team through outage", "Needed to restore service",
                "Coordinated cross-team response", "Resolved in 30 min",
                "Communication is key", List.of("incident"), List.of("leadership"), null
        );

        when(interviewStoryRepository.save(any(InterviewStory.class))).thenAnswer(inv -> {
            InterviewStory s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            return s;
        });

        InterviewStoryDto result = service.addStory(createDto);

        assertThat(result.situation()).isEqualTo("Led team through outage");
        assertThat(result.action()).isEqualTo("Coordinated cross-team response");
        assertThat(result.tags()).containsExactly("incident");
        assertThat(result.skills()).containsExactly("leadership");
        assertThat(result.id()).isNotNull();

        ArgumentCaptor<InterviewStory> captor = ArgumentCaptor.forClass(InterviewStory.class);
        verify(interviewStoryRepository).save(captor.capture());
        InterviewStory saved = captor.getValue();
        assertThat(saved.getSituation()).isEqualTo("Led team through outage");
        assertThat(saved.getTask()).isEqualTo("Needed to restore service");
    }

    // --- listStories tests ---

    @Test
    void listStories_returnsMappedDtos() {
        InterviewStory story1 = InterviewStory.builder()
                .id(UUID.randomUUID()).situation("S1").action("A1").result("R1")
                .tags(List.of("t1")).skills(List.of("s1"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        InterviewStory story2 = InterviewStory.builder()
                .id(UUID.randomUUID()).situation("S2").action("A2").result("R2")
                .tags(List.of("t2")).skills(List.of("s2"))
                .createdAt(LocalDateTime.now().minusDays(1)).updatedAt(LocalDateTime.now().minusDays(1)).build();

        when(interviewStoryRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(story1, story2));

        List<InterviewStoryDto> result = service.listStories();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).situation()).isEqualTo("S1");
        assertThat(result.get(1).situation()).isEqualTo("S2");
    }

    // --- getStory tests ---

    @Test
    void getStory_notFound_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(interviewStoryRepository.findById(id)).thenReturn(Optional.empty());

        Optional<InterviewStoryDto> result = service.getStory(id);

        assertThat(result).isEmpty();
    }

    @Test
    void getStory_found_returnsDto() {
        UUID id = UUID.randomUUID();
        InterviewStory story = InterviewStory.builder()
                .id(id).situation("Sit").task("Task").action("Act").result("Res")
                .reflection("Ref").tags(List.of("tag")).skills(List.of("skill"))
                .sourceJobId(UUID.randomUUID())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(interviewStoryRepository.findById(id)).thenReturn(Optional.of(story));

        Optional<InterviewStoryDto> result = service.getStory(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        assertThat(result.get().reflection()).isEqualTo("Ref");
    }

    // --- updateStory tests ---

    @Test
    void updateStory_notFound_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(interviewStoryRepository.findById(id)).thenReturn(Optional.empty());

        Optional<InterviewStoryDto> result = service.updateStory(id, new InterviewStoryCreateDto(
                "s", null, "a", "r", null, null, null, null));

        assertThat(result).isEmpty();
    }

    @Test
    void updateStory_found_updatesAndReturns() {
        UUID id = UUID.randomUUID();
        InterviewStory existing = InterviewStory.builder()
                .id(id).situation("old").action("old").result("old")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        InterviewStoryCreateDto updateDto = new InterviewStoryCreateDto(
                "new situation", "new task", "new action", "new result",
                "new reflection", List.of("updated"), List.of("Java"), null
        );

        when(interviewStoryRepository.findById(id)).thenReturn(Optional.of(existing));
        when(interviewStoryRepository.save(any(InterviewStory.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<InterviewStoryDto> result = service.updateStory(id, updateDto);

        assertThat(result).isPresent();
        assertThat(result.get().situation()).isEqualTo("new situation");
        assertThat(result.get().action()).isEqualTo("new action");
        assertThat(result.get().tags()).containsExactly("updated");
    }

    // --- deleteStory tests ---

    @Test
    void deleteStory_notFound_returnsFalse() {
        UUID id = UUID.randomUUID();
        when(interviewStoryRepository.existsById(id)).thenReturn(false);

        boolean result = service.deleteStory(id);

        assertThat(result).isFalse();
        verify(interviewStoryRepository, never()).deleteById(any());
    }

    @Test
    void deleteStory_exists_deletesAndReturnsTrue() {
        UUID id = UUID.randomUUID();
        when(interviewStoryRepository.existsById(id)).thenReturn(true);

        boolean result = service.deleteStory(id);

        assertThat(result).isTrue();
        verify(interviewStoryRepository).deleteById(id);
    }
}
