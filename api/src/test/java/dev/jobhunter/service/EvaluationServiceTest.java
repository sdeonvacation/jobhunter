package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.EvaluationDto;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobEvaluation;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.EvaluationArchetype;
import dev.jobhunter.model.enums.LegitimacyTier;
import dev.jobhunter.repository.JobEvaluationRepository;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock private AiProvider aiProvider;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobEvaluationRepository jobEvaluationRepository;
    @Mock private PersonalProfileLoader profileLoader;

    private EvaluationService service;

    private UUID jobId;
    private JobPosting job;
    private PersonalProfile profile;

    @BeforeEach
    void setUp() {
        service = new EvaluationService(aiProvider, jobPostingRepository, jobEvaluationRepository, profileLoader);

        jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("TechCorp").build();
        job = JobPosting.builder()
                .id(jobId)
                .title("Senior Backend Engineer")
                .company(company)
                .location("Berlin, Germany")
                .description("We are looking for a senior Java developer with Spring Boot experience.")
                .build();

        profile = new PersonalProfile(
                "Alice", "Staff Engineer", 10,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "expert", "FRAMEWORK")
                ),
                new PersonalProfile.Preferences(List.of("Berlin"), "FULL_TIME", 90000, List.of("senior"), List.of("en"), List.of()),
                null, null, null, null
        );
    }

    @Test
    void evaluate_jobNotFound_throws404() {
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(jobId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Job not found");
    }

    @Test
    void evaluate_alreadyExists_throws409() {
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(true);

        assertThatThrownBy(() -> service.evaluate(jobId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void evaluate_noDescription_throws422() {
        job.setDescription(null);
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(false);

        assertThatThrownBy(() -> service.evaluate(jobId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no description");
    }

    @Test
    void evaluate_blankDescription_throws422() {
        job.setDescription("   ");
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(false);

        assertThatThrownBy(() -> service.evaluate(jobId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no description");
    }

    @SuppressWarnings("unchecked")
    @Test
    void evaluate_allBlocks_success() {
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(false);
        when(profileLoader.getProfile()).thenReturn(profile);

        Map<String, Object> cvMatchResult = Map.of("overallFit", 4, "matchedSkills", List.of("Java"));
        Map<String, Object> legitimacyResult = Map.of("confidence", 5, "verdict", "legitimate");
        Map<String, Object> levelResult = Map.of("fit", 4, "seniorityMatch", "match");
        Map<String, Object> compResult = Map.of("attractiveness", 4, "companySize", "scaleup");
        Map<String, Object> custResult = Map.of("effortInverse", 4, "keyThemes", List.of("backend"));
        Map<String, Object> roleResult = Map.of("title", "Senior Backend Engineer", "seniority", "senior");
        Map<String, Object> interviewResult = Map.of("likelyTopics", List.of("Java", "Spring"));

        when(aiProvider.extract(anyString(), anyString(), eq(Map.class)))
                .thenReturn(roleResult)
                .thenReturn(cvMatchResult)
                .thenReturn(levelResult)
                .thenReturn(compResult)
                .thenReturn(custResult)
                .thenReturn(interviewResult)
                .thenReturn(legitimacyResult);

        when(jobEvaluationRepository.save(any(JobEvaluation.class))).thenAnswer(inv -> inv.getArgument(0));

        EvaluationDto result = service.evaluate(jobId, null);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.jobTitle()).isEqualTo("Senior Backend Engineer");
        assertThat(result.companyName()).isEqualTo("TechCorp");
        assertThat(result.roleSummary()).isEqualTo(roleResult);
        assertThat(result.cvMatch()).isEqualTo(cvMatchResult);
        assertThat(result.overallScore()).isBetween(1, 5);
        assertThat(result.archetype()).isNotNull();
        assertThat(result.legitimacyTier()).isEqualTo(LegitimacyTier.GREEN);
        assertThat(result.descriptionFingerprint()).isNotNull();
        assertThat(result.evaluatedAt()).isNotNull();

        verify(jobEvaluationRepository).save(any(JobEvaluation.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void evaluate_specificBlocks_onlyEvaluatesRequested() {
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(false);
        when(profileLoader.getProfile()).thenReturn(profile);

        Map<String, Object> cvMatchResult = Map.of("overallFit", 3);
        when(aiProvider.extract(anyString(), anyString(), eq(Map.class))).thenReturn(cvMatchResult);
        when(jobEvaluationRepository.save(any(JobEvaluation.class))).thenAnswer(inv -> inv.getArgument(0));

        EvaluationDto result = service.evaluate(jobId, List.of("cvMatch"));

        assertThat(result.cvMatch()).isEqualTo(cvMatchResult);
        assertThat(result.roleSummary()).isNull();
        assertThat(result.legitimacy()).isNull();

        // Only one AI call for one block
        verify(aiProvider, times(1)).extract(anyString(), anyString(), eq(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void evaluate_aiFailure_returnsErrorInBlock() {
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(false);
        when(profileLoader.getProfile()).thenReturn(profile);

        when(aiProvider.extract(anyString(), anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("AI timeout"));
        when(jobEvaluationRepository.save(any(JobEvaluation.class))).thenAnswer(inv -> inv.getArgument(0));

        EvaluationDto result = service.evaluate(jobId, List.of("roleSummary"));

        assertThat(result.roleSummary()).containsEntry("error", "AI timeout");
        verify(jobEvaluationRepository).save(any(JobEvaluation.class));
    }

    @Test
    void evaluate_savedEntity_hasCorrectFingerprint() {
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobEvaluationRepository.existsByJobId(jobId)).thenReturn(false);
        when(profileLoader.getProfile()).thenReturn(profile);
        when(aiProvider.extract(anyString(), anyString(), any())).thenReturn(Map.of("overallFit", 3));
        when(jobEvaluationRepository.save(any(JobEvaluation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.evaluate(jobId, List.of("cvMatch"));

        ArgumentCaptor<JobEvaluation> captor = ArgumentCaptor.forClass(JobEvaluation.class);
        verify(jobEvaluationRepository).save(captor.capture());

        String expectedFingerprint = EvaluationService.sha256(job.getDescription());
        assertThat(captor.getValue().getDescriptionFingerprint()).isEqualTo(expectedFingerprint);
    }

    @Test
    void getEvaluation_found_returnsDto() {
        JobEvaluation eval = JobEvaluation.builder()
                .id(UUID.randomUUID())
                .job(job)
                .overallScore(4)
                .archetype(EvaluationArchetype.PERFECT_FIT)
                .legitimacyTier(LegitimacyTier.GREEN)
                .cvMatch(Map.of("overallFit", 4))
                .build();

        when(jobEvaluationRepository.findByJobId(jobId)).thenReturn(Optional.of(eval));
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        EvaluationDto result = service.getEvaluation(jobId);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.overallScore()).isEqualTo(4);
        assertThat(result.archetype()).isEqualTo(EvaluationArchetype.PERFECT_FIT);
    }

    @Test
    void getEvaluation_notFound_throws404() {
        when(jobEvaluationRepository.findByJobId(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvaluation(jobId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No evaluation");
    }

    @Test
    void deleteEvaluation_delegatesToRepository() {
        service.deleteEvaluation(jobId);
        verify(jobEvaluationRepository).deleteByJobId(jobId);
    }

    @Test
    void computeOverallScore_allHighScores_returns5() {
        Map<String, Object> cvMatch = Map.of("overallFit", 5);
        Map<String, Object> legitimacy = Map.of("confidence", 5);
        Map<String, Object> levelStrategy = Map.of("fit", 5);
        Map<String, Object> compResearch = Map.of("attractiveness", 5);
        Map<String, Object> customizationPlan = Map.of("effortInverse", 5);

        int score = service.computeOverallScore(cvMatch, legitimacy, levelStrategy, compResearch, customizationPlan);
        assertThat(score).isEqualTo(5);
    }

    @Test
    void computeOverallScore_allLowScores_returns1() {
        Map<String, Object> cvMatch = Map.of("overallFit", 1);
        Map<String, Object> legitimacy = Map.of("confidence", 1);
        Map<String, Object> levelStrategy = Map.of("fit", 1);
        Map<String, Object> compResearch = Map.of("attractiveness", 1);
        Map<String, Object> customizationPlan = Map.of("effortInverse", 1);

        int score = service.computeOverallScore(cvMatch, legitimacy, levelStrategy, compResearch, customizationPlan);
        assertThat(score).isEqualTo(1);
    }

    @Test
    void computeOverallScore_nullBlocks_usesDefaults() {
        int score = service.computeOverallScore(null, null, null, null, null);
        assertThat(score).isEqualTo(3); // all defaults are 3
    }

    @Test
    void computeOverallScore_weightedCorrectly() {
        // cvMatch=5*0.35 + legitimacy=5*0.20 + level=1*0.20 + comp=1*0.15 + cust=1*0.10 = 1.75+1.0+0.2+0.15+0.1 = 3.2 -> rounds to 3
        Map<String, Object> cvMatch = Map.of("overallFit", 5);
        Map<String, Object> legitimacy = Map.of("confidence", 5);
        Map<String, Object> levelStrategy = Map.of("fit", 1);
        Map<String, Object> compResearch = Map.of("attractiveness", 1);
        Map<String, Object> customizationPlan = Map.of("effortInverse", 1);

        int score = service.computeOverallScore(cvMatch, legitimacy, levelStrategy, compResearch, customizationPlan);
        assertThat(score).isEqualTo(3);
    }

    @Test
    void determineArchetype_perfectFit() {
        Map<String, Object> cvMatch = Map.of("overallFit", 5);
        Map<String, Object> levelStrategy = Map.of("fit", 4);

        EvaluationArchetype archetype = service.determineArchetype(4, cvMatch, levelStrategy);
        assertThat(archetype).isEqualTo(EvaluationArchetype.PERFECT_FIT);
    }

    @Test
    void determineArchetype_growthStretch() {
        Map<String, Object> cvMatch = Map.of("overallFit", 3);
        Map<String, Object> levelStrategy = Map.of("fit", 4);

        EvaluationArchetype archetype = service.determineArchetype(3, cvMatch, levelStrategy);
        assertThat(archetype).isEqualTo(EvaluationArchetype.GROWTH_STRETCH);
    }

    @Test
    void determineArchetype_lateralMove() {
        Map<String, Object> cvMatch = Map.of("overallFit", 4);
        Map<String, Object> levelStrategy = Map.of("fit", 3);

        EvaluationArchetype archetype = service.determineArchetype(3, cvMatch, levelStrategy);
        assertThat(archetype).isEqualTo(EvaluationArchetype.LATERAL_MOVE);
    }

    @Test
    void determineArchetype_redFlag() {
        Map<String, Object> cvMatch = Map.of("overallFit", 1);
        Map<String, Object> levelStrategy = Map.of("fit", 1);

        EvaluationArchetype archetype = service.determineArchetype(2, cvMatch, levelStrategy);
        assertThat(archetype).isEqualTo(EvaluationArchetype.RED_FLAG);
    }

    @Test
    void determineArchetype_longShot() {
        Map<String, Object> cvMatch = Map.of("overallFit", 2);
        Map<String, Object> levelStrategy = Map.of("fit", 2);

        EvaluationArchetype archetype = service.determineArchetype(3, cvMatch, levelStrategy);
        assertThat(archetype).isEqualTo(EvaluationArchetype.LONG_SHOT);
    }

    @Test
    void determineLegitimacyTier_green() {
        assertThat(service.determineLegitimacyTier(Map.of("confidence", 4))).isEqualTo(LegitimacyTier.GREEN);
        assertThat(service.determineLegitimacyTier(Map.of("confidence", 5))).isEqualTo(LegitimacyTier.GREEN);
    }

    @Test
    void determineLegitimacyTier_amber() {
        assertThat(service.determineLegitimacyTier(Map.of("confidence", 3))).isEqualTo(LegitimacyTier.AMBER);
        assertThat(service.determineLegitimacyTier(Map.of("confidence", 2.5))).isEqualTo(LegitimacyTier.AMBER);
    }

    @Test
    void determineLegitimacyTier_red() {
        assertThat(service.determineLegitimacyTier(Map.of("confidence", 2))).isEqualTo(LegitimacyTier.RED);
        assertThat(service.determineLegitimacyTier(Map.of("confidence", 1))).isEqualTo(LegitimacyTier.RED);
    }

    @Test
    void determineLegitimacyTier_nullBlock_defaultsToAmber() {
        assertThat(service.determineLegitimacyTier(null)).isEqualTo(LegitimacyTier.AMBER);
    }

    @Test
    void sha256_producesConsistentHash() {
        String input = "test description";
        String hash1 = EvaluationService.sha256(input);
        String hash2 = EvaluationService.sha256(input);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 = 64 hex chars
    }

    @Test
    void sha256_nullInput_returnsNull() {
        assertThat(EvaluationService.sha256(null)).isNull();
    }

    @Test
    void sha256_differentInputs_produceDifferentHashes() {
        String hash1 = EvaluationService.sha256("description A");
        String hash2 = EvaluationService.sha256("description B");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
