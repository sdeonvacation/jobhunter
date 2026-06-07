package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.TailoredResumeDto;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.JobSkill;
import dev.jobhunter.model.enums.SkillCategory;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.JobSkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeTailoringServiceTest {

    @Mock private AiProvider aiProvider;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobSkillRepository jobSkillRepository;
    @Mock private PersonalProfileLoader profileLoader;

    private ResumeTailoringService service;

    @BeforeEach
    void setUp() {
        service = new ResumeTailoringService(aiProvider, jobPostingRepository, jobSkillRepository, profileLoader);
    }

    @Test
    void tailor_aiNotAvailable_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(false);

        Optional<TailoredResumeDto> result = service.tailor(UUID.randomUUID(), null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void tailor_jobNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<TailoredResumeDto> result = service.tailor(jobId, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void tailor_success_returnsValidatedResult() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Backend Dev")
                .company(company).description("Build APIs").build();
        JobSkill skill = new JobSkill();
        skill.setSkillName("Java");
        skill.setCategory(SkillCategory.LANGUAGE);
        skill.setRequired(true);

        PersonalProfile profile = new PersonalProfile(
                "John", "Engineer", 8,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE"),
                        new PersonalProfile.ProfileSkill("Spring", "advanced", "FRAMEWORK")
                ),
                new PersonalProfile.Preferences(
                        List.of("Berlin"), "FULL_TIME", 80000,
                        List.of("senior"), List.of("en"), List.of()
                ),
                null, null, null
        , null);

        // AI returns skills including one the profile doesn't have
        ResumeTailoringService.TailoringResponse aiResponse = new ResumeTailoringService.TailoringResponse(
                "Experienced backend engineer with Java expertise",
                List.of("Java", "Spring", "Kubernetes"), // Kubernetes not in profile
                List.of("Built high-traffic APIs", "Led Java migration")
        );

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(jobId)).thenReturn(List.of(skill));
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.extract(anyString(), anyString(), eq(ResumeTailoringService.TailoringResponse.class)))
                .thenReturn(aiResponse);

        Optional<TailoredResumeDto> result = service.tailor(jobId, "backend", null);

        assertThat(result).isPresent();
        TailoredResumeDto dto = result.get();
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.jobTitle()).isEqualTo("Backend Dev");
        assertThat(dto.companyName()).isEqualTo("Acme Corp");
        assertThat(dto.tailoredSummary()).contains("Java");
        assertThat(dto.emphasis()).isEqualTo("backend");

        // Kubernetes should be filtered out (not in profile)
        assertThat(dto.highlightedSkills()).containsExactly("Java", "Spring");
        assertThat(dto.highlightedSkills()).doesNotContain("Kubernetes");

        assertThat(dto.reorderedExperiencePoints()).hasSize(2);
    }

    @Test
    void tailor_aiThrows_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Fail Corp").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev")
                .company(company).description("test").build();

        PersonalProfile profile = new PersonalProfile(
                "John", "Dev", 5, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null
        , null);

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(jobId)).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.extract(anyString(), anyString(), any())).thenThrow(new RuntimeException("API error"));

        Optional<TailoredResumeDto> result = service.tailor(jobId, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void tailor_withExcludeSkills_passedToPrompt() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Test").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev")
                .company(company).description("Build stuff").build();

        PersonalProfile profile = new PersonalProfile(
                "Jane", "Dev", 3,
                List.of(new PersonalProfile.ProfileSkill("Python", "advanced", "LANGUAGE")),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null
        , null);

        ResumeTailoringService.TailoringResponse aiResponse = new ResumeTailoringService.TailoringResponse(
                "Python developer", List.of("Python"), List.of("Wrote scripts")
        );

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(jobId)).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.extract(anyString(), anyString(), eq(ResumeTailoringService.TailoringResponse.class)))
                .thenReturn(aiResponse);

        Optional<TailoredResumeDto> result = service.tailor(jobId, null, List.of("Java"));

        assertThat(result).isPresent();
        // Verify AI was called (prompt would contain exclude clause)
        verify(aiProvider).extract(anyString(), contains("Exclude these skills"), any());
    }
}
