package dev.jobhunter.controller;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.JobSkill;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.MatchScore;
import dev.jobhunter.model.OpportunityScore;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.Recommendation;
import dev.jobhunter.model.enums.RemoteType;
import dev.jobhunter.model.enums.SkillCategory;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.JobSkillRepository;
import dev.jobhunter.service.DailyDigestService;
import dev.jobhunter.service.DailyDigestService.DigestSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobSkillRepository jobSkillRepository;
    @Mock private DailyDigestService dailyDigestService;

    private JobController controller;

    @BeforeEach
    void setUp() {
        controller = new JobController(jobPostingRepository, jobSkillRepository, dailyDigestService);
    }

    @Test
    void searchJobs_returnsPageOfSummaries() {
        Company company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();
        MatchScore match = new MatchScore();
        match.setOverallScore(85);
        match.setRecommendation(Recommendation.APPLY);
        OpportunityScore opp = new OpportunityScore();
        opp.setScore(72);

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Backend Dev")
                .company(company)
                .location("Berlin")
                .isRemote(RemoteType.HYBRID)
                .matchScore(match)
                .opportunityScore(opp)
                .salaryMin(BigDecimal.valueOf(80000))
                .salaryMax(BigDecimal.valueOf(100000))
                .salaryCurrency("EUR")
                .postedDate(LocalDate.now())
                .build();

        Page<JobPosting> page = new PageImpl<>(List.of(job));
        when(jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceNotIn(eq(FilterDecision.KEEP), anyList(), any(Pageable.class)))
                .thenReturn(page);

        var result = controller.searchJobs(null, null, null, null, null, "matchScore", 0, 20);

        assertThat(result.getContent()).hasSize(1);
        var dto = result.getContent().get(0);
        assertThat(dto.title()).isEqualTo("Backend Dev");
        assertThat(dto.companyName()).isEqualTo("TestCo");
        assertThat(dto.matchScore()).isEqualTo(85);
        assertThat(dto.opportunityScore()).isEqualTo(72);
        assertThat(dto.recommendation()).isEqualTo("APPLY");
    }

    @Test
    void getJob_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(jobPostingRepository.findById(id)).thenReturn(Optional.empty());

        var response = controller.getJob(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getJob_found_returnsDetail() {
        UUID id = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme").build();
        JobPosting job = JobPosting.builder()
                .id(id).title("Full Stack").company(company)
                .description("Build things").location("Munich")
                .build();

        JobSkill skill = new JobSkill();
        skill.setSkillName("TypeScript");
        skill.setCategory(SkillCategory.LANGUAGE);
        skill.setRequired(true);

        when(jobPostingRepository.findById(id)).thenReturn(Optional.of(job));
        when(jobSkillRepository.findByJobId(id)).thenReturn(List.of(skill));

        var response = controller.getJob(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.title()).isEqualTo("Full Stack");
        assertThat(dto.techStack().languages()).containsExactly("TypeScript");
    }

    @Test
    void getTechStack_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(jobPostingRepository.existsById(id)).thenReturn(false);

        var response = controller.getTechStack(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTechStack_found_returnsCategorized() {
        UUID id = UUID.randomUUID();
        JobSkill lang = new JobSkill();
        lang.setSkillName("Java");
        lang.setCategory(SkillCategory.LANGUAGE);
        JobSkill fw = new JobSkill();
        fw.setSkillName("Spring");
        fw.setCategory(SkillCategory.FRAMEWORK);
        JobSkill db = new JobSkill();
        db.setSkillName("PostgreSQL");
        db.setCategory(SkillCategory.DATABASE);

        when(jobPostingRepository.existsById(id)).thenReturn(true);
        when(jobSkillRepository.findByJobId(id)).thenReturn(List.of(lang, fw, db));

        var response = controller.getTechStack(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var dto = response.getBody();
        assertThat(dto.languages()).containsExactly("Java");
        assertThat(dto.frameworks()).containsExactly("Spring");
        assertThat(dto.databases()).containsExactly("PostgreSQL");
    }

    @Test
    void getScore_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(jobPostingRepository.findById(id)).thenReturn(Optional.empty());

        var response = controller.getScore(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getDailyDigest_returnsDigest() {
        DigestSnapshot snapshot = new DigestSnapshot(LocalDate.now(), 5, "Backend Dev", "Acme", 90);
        when(dailyDigestService.computeDigest()).thenReturn(snapshot);
        when(jobPostingRepository.findRecentlyPostedJobs(eq(FilterDecision.KEEP), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = controller.getDailyDigest();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().newJobsCount()).isEqualTo(5);
        assertThat(response.getBody().date()).isEqualTo(LocalDate.now());
    }
}
