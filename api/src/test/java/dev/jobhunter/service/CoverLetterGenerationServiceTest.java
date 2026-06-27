package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.CoverLetter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.CoverLetterRepository;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoverLetterGenerationServiceTest {

    @Mock private AiProvider aiProvider;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private CoverLetterRepository coverLetterRepository;
    @Mock private PersonalProfileLoader profileLoader;

    private CoverLetterGenerationService service;

    @BeforeEach
    void setUp() {
        service = new CoverLetterGenerationService(
                aiProvider, jobPostingRepository, coverLetterRepository, profileLoader);
    }

    private PersonalProfile testProfile() {
        return new PersonalProfile(
                "Alice Smith", "Senior Backend Engineer", 8,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "expert", "FRAMEWORK"),
                        new PersonalProfile.ProfileSkill("Kubernetes", "advanced", "DEVOPS")
                ),
                new PersonalProfile.Preferences(
                        List.of("Berlin"), "FULL_TIME", 80000, List.of("senior"), List.of("en"), List.of()
                ),
                null, null, null, null
        );
    }

    private JobPosting testJob(UUID jobId) {
        Company company = Company.builder().id(UUID.randomUUID()).name("TechCorp").build();
        return JobPosting.builder()
                .id(jobId)
                .title("Senior Backend Engineer")
                .company(company)
                .description("We are looking for a Senior Backend Engineer with Java, Spring Boot, and Kubernetes experience.")
                .location("Berlin, Germany")
                .build();
    }

    @Test
    void generate_aiNotAvailable_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(false);

        Optional<CoverLetter> result = service.generate(UUID.randomUUID(), "professional", null, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(jobPostingRepository);
    }

    @Test
    void generate_jobNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<CoverLetter> result = service.generate(jobId, "professional", null, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(coverLetterRepository);
    }

    @Test
    void generate_jobHasNoDescription_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").description(null).build();

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        Optional<CoverLetter> result = service.generate(jobId, null, null, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(coverLetterRepository);
    }

    @Test
    void generate_jobHasBlankDescription_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").description("   ").build();

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        Optional<CoverLetter> result = service.generate(jobId, "professional", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void generate_success_persistsAndReturns() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = testJob(jobId);
        PersonalProfile profile = testProfile();
        List<String> extractedKeywords = List.of("Java", "Spring Boot", "Kubernetes", "microservices");
        String generatedContent = "Dear Hiring Manager,\n\nI am writing to express my interest in the Senior Backend Engineer role at TechCorp...";

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(aiProvider.extract(anyString(), anyString(), eq(List.class))).thenReturn(extractedKeywords);
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.generate(anyString(), anyString())).thenReturn(generatedContent);
        when(coverLetterRepository.countByJobId(jobId)).thenReturn(0);
        when(coverLetterRepository.save(any(CoverLetter.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<CoverLetter> result = service.generate(
                jobId, "enthusiastic", "backend scalability", List.of("distributed systems", "API design"));

        assertThat(result).isPresent();
        CoverLetter cl = result.get();
        assertThat(cl.getContent()).isEqualTo(generatedContent);
        assertThat(cl.getTone()).isEqualTo("enthusiastic");
        assertThat(cl.getFocus()).isEqualTo("backend scalability");
        assertThat(cl.getAngles()).containsExactly("distributed systems", "API design");
        assertThat(cl.getKeywordsMirrored()).isEqualTo(extractedKeywords);
        assertThat(cl.getVersion()).isEqualTo(1);
        assertThat(cl.getGeneratedAt()).isNotNull();
        assertThat(cl.getJob()).isEqualTo(job);
    }

    @Test
    void generate_nullTone_defaultsToProfessional() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = testJob(jobId);

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(aiProvider.extract(anyString(), anyString(), eq(List.class))).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(testProfile());
        when(aiProvider.generate(anyString(), anyString())).thenReturn("content");
        when(coverLetterRepository.countByJobId(jobId)).thenReturn(0);
        when(coverLetterRepository.save(any(CoverLetter.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<CoverLetter> result = service.generate(jobId, null, null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getTone()).isEqualTo("professional");
    }

    @Test
    void generate_incrementsVersion() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = testJob(jobId);

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(aiProvider.extract(anyString(), anyString(), eq(List.class))).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(testProfile());
        when(aiProvider.generate(anyString(), anyString())).thenReturn("v2 content");
        when(coverLetterRepository.countByJobId(jobId)).thenReturn(2);
        when(coverLetterRepository.save(any(CoverLetter.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<CoverLetter> result = service.generate(jobId, "professional", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getVersion()).isEqualTo(3);
    }

    @Test
    void generate_keywordExtractionFails_proceedsWithEmptyKeywords() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = testJob(jobId);

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(aiProvider.extract(anyString(), anyString(), eq(List.class)))
                .thenThrow(new RuntimeException("extraction failed"));
        when(profileLoader.getProfile()).thenReturn(testProfile());
        when(aiProvider.generate(anyString(), anyString())).thenReturn("letter content");
        when(coverLetterRepository.countByJobId(jobId)).thenReturn(0);
        when(coverLetterRepository.save(any(CoverLetter.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<CoverLetter> result = service.generate(jobId, "professional", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getKeywordsMirrored()).isEmpty();
    }

    @Test
    void generate_aiGenerateThrows_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = testJob(jobId);

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(aiProvider.extract(anyString(), anyString(), eq(List.class))).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(testProfile());
        when(aiProvider.generate(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        Optional<CoverLetter> result = service.generate(jobId, "professional", null, null);

        assertThat(result).isEmpty();
        verify(coverLetterRepository, never()).save(any());
    }

    @Test
    void generate_promptContainsJobDetailsAndKeywords() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = testJob(jobId);
        List<String> keywords = List.of("Java", "Spring Boot");

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(aiProvider.extract(anyString(), anyString(), eq(List.class))).thenReturn(keywords);
        when(profileLoader.getProfile()).thenReturn(testProfile());
        when(aiProvider.generate(anyString(), anyString())).thenReturn("content");
        when(coverLetterRepository.countByJobId(jobId)).thenReturn(0);
        when(coverLetterRepository.save(any(CoverLetter.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generate(jobId, "professional", "microservices", List.of("scalability"));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiProvider).generate(anyString(), userPromptCaptor.capture());

        String prompt = userPromptCaptor.getValue();
        assertThat(prompt).contains("Senior Backend Engineer");
        assertThat(prompt).contains("TechCorp");
        assertThat(prompt).contains("Java, Spring Boot");
        assertThat(prompt).contains("Focus on: microservices");
        assertThat(prompt).contains("Angles to emphasize: scalability");
        assertThat(prompt).contains("Alice Smith");
    }

    @Test
    void getForJob_delegatesToRepository() {
        UUID jobId = UUID.randomUUID();
        CoverLetter cl = CoverLetter.builder().id(UUID.randomUUID()).version(2).build();
        when(coverLetterRepository.findFirstByJobIdOrderByVersionDesc(jobId))
                .thenReturn(Optional.of(cl));

        Optional<CoverLetter> result = service.getForJob(jobId);

        assertThat(result).isPresent();
        assertThat(result.get().getVersion()).isEqualTo(2);
    }

    @Test
    void getForJob_noneExists_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(coverLetterRepository.findFirstByJobIdOrderByVersionDesc(jobId))
                .thenReturn(Optional.empty());

        Optional<CoverLetter> result = service.getForJob(jobId);

        assertThat(result).isEmpty();
    }

    @Test
    void list_returnsAllVersions() {
        UUID jobId = UUID.randomUUID();
        List<CoverLetter> letters = List.of(
                CoverLetter.builder().version(2).build(),
                CoverLetter.builder().version(1).build()
        );
        when(coverLetterRepository.findByJobIdOrderByVersionDesc(jobId)).thenReturn(letters);

        List<CoverLetter> result = service.list(jobId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getVersion()).isEqualTo(2);
    }

    @Test
    void update_success_updatesContentAndEditedAt() {
        UUID coverId = UUID.randomUUID();
        CoverLetter existing = CoverLetter.builder()
                .id(coverId)
                .content("original")
                .generatedAt(LocalDateTime.now().minusHours(1))
                .build();

        when(coverLetterRepository.findById(coverId)).thenReturn(Optional.of(existing));
        when(coverLetterRepository.save(any(CoverLetter.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<CoverLetter> result = service.update(coverId, "edited content");

        assertThat(result).isPresent();
        assertThat(result.get().getContent()).isEqualTo("edited content");
        assertThat(result.get().getEditedAt()).isNotNull();
    }

    @Test
    void update_notFound_returnsEmpty() {
        UUID coverId = UUID.randomUUID();
        when(coverLetterRepository.findById(coverId)).thenReturn(Optional.empty());

        Optional<CoverLetter> result = service.update(coverId, "content");

        assertThat(result).isEmpty();
        verify(coverLetterRepository, never()).save(any());
    }
}
