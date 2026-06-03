package dev.jobhub.dto;

import dev.jobhub.model.*;
import dev.jobhub.model.enums.*;
import dev.jobhub.service.PersonalProfile;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMapperTest {

    @Test
    void toJobSummary_mapsAllFields() {
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme").build();
        MatchScore match = new MatchScore();
        match.setOverallScore(82);
        match.setRecommendation(Recommendation.APPLY);
        OpportunityScore opp = new OpportunityScore();
        opp.setScore(75);

        JobSkill skill1 = new JobSkill();
        skill1.setSkillName("Java");
        skill1.setCategory(SkillCategory.LANGUAGE);
        skill1.setRequired(true);
        JobSkill skill2 = new JobSkill();
        skill2.setSkillName("Docker");
        skill2.setCategory(SkillCategory.TOOL);
        skill2.setRequired(false);

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Backend Engineer")
                .company(company)
                .location("Berlin, DE")
                .isRemote(RemoteType.HYBRID)
                .matchScore(match)
                .opportunityScore(opp)
                .skills(List.of(skill1, skill2))
                .salaryMin(BigDecimal.valueOf(70000))
                .salaryMax(BigDecimal.valueOf(95000))
                .salaryCurrency("EUR")
                .postedDate(LocalDate.of(2024, 6, 1))
                .source(AtsType.GREENHOUSE)
                .build();

        JobSummaryDto dto = DtoMapper.toJobSummary(job);

        assertThat(dto.id()).isEqualTo(job.getId());
        assertThat(dto.title()).isEqualTo("Backend Engineer");
        assertThat(dto.companyName()).isEqualTo("Acme");
        assertThat(dto.location()).isEqualTo("Berlin, DE");
        assertThat(dto.remoteType()).isEqualTo("HYBRID");
        assertThat(dto.matchScore()).isEqualTo(82);
        assertThat(dto.opportunityScore()).isEqualTo(75);
        assertThat(dto.recommendation()).isEqualTo("APPLY");
        assertThat(dto.topSkills()).containsExactly("Java"); // only required skills
        assertThat(dto.salaryMin()).isEqualByComparingTo(BigDecimal.valueOf(70000));
        assertThat(dto.source()).isEqualTo("GREENHOUSE");
    }

    @Test
    void toJobSummary_nullScores_defaultsToZero() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Dev")
                .build();

        JobSummaryDto dto = DtoMapper.toJobSummary(job);

        assertThat(dto.matchScore()).isZero();
        assertThat(dto.opportunityScore()).isZero();
        assertThat(dto.recommendation()).isNull();
        assertThat(dto.topSkills()).isEmpty();
    }

    @Test
    void toTechStack_groupsByCategory() {
        JobSkill java = new JobSkill();
        java.setSkillName("Java");
        java.setCategory(SkillCategory.LANGUAGE);
        JobSkill spring = new JobSkill();
        spring.setSkillName("Spring");
        spring.setCategory(SkillCategory.FRAMEWORK);
        JobSkill pg = new JobSkill();
        pg.setSkillName("PostgreSQL");
        pg.setCategory(SkillCategory.DATABASE);
        JobSkill aws = new JobSkill();
        aws.setSkillName("AWS");
        aws.setCategory(SkillCategory.CLOUD);
        JobSkill git = new JobSkill();
        git.setSkillName("Git");
        git.setCategory(SkillCategory.TOOL);
        JobSkill agile = new JobSkill();
        agile.setSkillName("Agile");
        agile.setCategory(SkillCategory.METHODOLOGY);

        TechStackDto dto = DtoMapper.toTechStack(List.of(java, spring, pg, aws, git, agile));

        assertThat(dto.languages()).containsExactly("Java");
        assertThat(dto.frameworks()).containsExactly("Spring");
        assertThat(dto.databases()).containsExactly("PostgreSQL");
        assertThat(dto.cloud()).containsExactly("AWS");
        assertThat(dto.tools()).containsExactly("Git");
        assertThat(dto.methodologies()).containsExactly("Agile");
    }

    @Test
    void toCompanySummary_mapsFields() {
        CareerEndpoint ep = new CareerEndpoint();
        ep.setId(UUID.randomUUID());

        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("TechCorp")
                .domain("techcorp.com")
                .country("DE")
                .status(CompanyStatus.ACTIVE)
                .priorityScore(85.5)
                .interviewRate(0.35)
                .totalApplications(20)
                .careerEndpoints(List.of(ep))
                .build();

        CompanySummaryDto dto = DtoMapper.toCompanySummary(company);

        assertThat(dto.name()).isEqualTo("TechCorp");
        assertThat(dto.domain()).isEqualTo("techcorp.com");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.priorityScore()).isEqualTo(85.5);
        assertThat(dto.endpointCount()).isEqualTo(1);
        assertThat(dto.interviewRate()).isEqualTo(0.35);
    }

    @Test
    void toApplication_mapsWithOutcomes() {
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().name("Co").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Engineer").company(company).build();

        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.INTERVIEWING);
        app.setAppliedDate(LocalDate.of(2024, 5, 15));
        app.setResumeVariant("tailored-v1");
        app.setNotes("Great opportunity");
        app.setCreatedAt(LocalDateTime.now());

        JobOutcome outcome = new JobOutcome();
        outcome.setId(UUID.randomUUID());
        outcome.setStage(OutcomeStage.PHONE_SCREEN);
        outcome.setOccurredAt(LocalDate.of(2024, 5, 20));
        outcome.setNotes("Went well");

        ApplicationDto dto = DtoMapper.toApplication(app, List.of(outcome));

        assertThat(dto.id()).isEqualTo(appId);
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.jobTitle()).isEqualTo("Engineer");
        assertThat(dto.companyName()).isEqualTo("Co");
        assertThat(dto.status()).isEqualTo("INTERVIEWING");
        assertThat(dto.resumeVariant()).isEqualTo("tailored-v1");
        assertThat(dto.outcomes()).hasSize(1);
        assertThat(dto.outcomes().get(0).stage()).isEqualTo("PHONE_SCREEN");
    }

    @Test
    void toProfile_mapsProfileData() {
        PersonalProfile profile = new PersonalProfile(
                "Alice", "Senior Dev", 8,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE")),
                new PersonalProfile.Preferences(
                        List.of("Berlin", "Munich"), "FULL_TIME", 80000,
                        List.of("senior"), List.of("en", "de"), List.of("finance")
                )
        );

        ProfileDto dto = DtoMapper.toProfile(profile);

        assertThat(dto.name()).isEqualTo("Alice");
        assertThat(dto.title()).isEqualTo("Senior Dev");
        assertThat(dto.yearsOfExperience()).isEqualTo(8);
        assertThat(dto.skills()).hasSize(1);
        assertThat(dto.skills().get(0).name()).isEqualTo("Java");
        assertThat(dto.preferences().locations()).containsExactly("Berlin", "Munich");
        assertThat(dto.preferences().minSalaryEur()).isEqualTo(80000);
    }
}
