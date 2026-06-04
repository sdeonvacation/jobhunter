package dev.jobhub.service;

import dev.jobhub.ai.AiProvider;
import dev.jobhub.dto.CoverLetterDto;
import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.JobSkill;
import dev.jobhub.model.enums.SkillCategory;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.repository.JobSkillRepository;
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
class CoverLetterServiceTest {

    @Mock private AiProvider aiProvider;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobSkillRepository jobSkillRepository;
    @Mock private PersonalProfileLoader profileLoader;

    private CoverLetterService service;

    @BeforeEach
    void setUp() {
        service = new CoverLetterService(aiProvider, jobPostingRepository, jobSkillRepository, profileLoader);
    }

    @Test
    void generate_aiNotAvailable_returnsEmpty() {
        when(aiProvider.isAvailable()).thenReturn(false);

        Optional<CoverLetterDto> result = service.generate(UUID.randomUUID(), "professional", null);

        assertThat(result).isEmpty();
    }

    @Test
    void generate_jobNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<CoverLetterDto> result = service.generate(jobId, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void generate_success_returnsCoverLetter() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("TechCorp").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Senior Engineer")
                .company(company).description("Join our team").location("Berlin").build();

        JobSkill skill = new JobSkill();
        skill.setSkillName("Java");
        skill.setCategory(SkillCategory.LANGUAGE);
        skill.setRequired(true);

        PersonalProfile profile = new PersonalProfile(
                "Alice", "Staff Engineer", 10,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE")),
                new PersonalProfile.Preferences(
                        List.of("Berlin"), "FULL_TIME", 90000, List.of("senior"), List.of("en"), List.of()
                ),
                null, null
        );

        String generatedLetter = "Dear Hiring Manager,\n\nI am writing to express my interest...";

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(jobId)).thenReturn(List.of(skill));
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.generate(anyString(), anyString())).thenReturn(generatedLetter);

        Optional<CoverLetterDto> result = service.generate(jobId, "enthusiastic", "leadership");

        assertThat(result).isPresent();
        CoverLetterDto dto = result.get();
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.jobTitle()).isEqualTo("Senior Engineer");
        assertThat(dto.companyName()).isEqualTo("TechCorp");
        assertThat(dto.content()).isEqualTo(generatedLetter);
        assertThat(dto.tone()).isEqualTo("enthusiastic");
    }

    @Test
    void generate_nullTone_defaultsToProfessional() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Co").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev")
                .company(company).description("test").build();

        PersonalProfile profile = new PersonalProfile(
                "Bob", "Dev", 3, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null
        );

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(jobId)).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("Letter content");

        Optional<CoverLetterDto> result = service.generate(jobId, null, null);

        assertThat(result).isPresent();
        assertThat(result.get().tone()).isEqualTo("professional");
    }

    @Test
    void generate_aiThrows_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("X").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Y")
                .company(company).description("z").build();

        PersonalProfile profile = new PersonalProfile(
                "C", "D", 1, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null
        );

        when(aiProvider.isAvailable()).thenReturn(true);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(jobId)).thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.generate(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        Optional<CoverLetterDto> result = service.generate(jobId, "casual", null);

        assertThat(result).isEmpty();
    }
}
