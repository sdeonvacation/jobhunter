package dev.jobhub.scoring;

import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.MatchScore;
import dev.jobhub.model.enums.RemoteType;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpportunityScorerTest {

    @Mock
    private PersonalProfileLoader profileLoader;

    private OpportunityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new OpportunityScorer(profileLoader);
        when(profileLoader.getProfile()).thenReturn(testProfile());
    }

    @Test
    void shouldReturnNeutralForColdStart() {
        // No company, no salary, no match score = all neutral (50)
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Software Engineer")
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(job, null);

        // All factors at 50 (neutral) → composite also around 50
        assertThat(result.score()).isBetween(45, 55);
        assertThat(result.breakdown()).containsKey("matchScore");
        assertThat(result.breakdown().get("matchScore")).isEqualTo(50);
    }

    @Test
    void shouldScoreHighWithAllPositiveSignals() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.8)
                .priorityScore(85.0)
                .country("Germany")
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Staff Backend Engineer")
                .company(company)
                .salaryMax(new BigDecimal("120000"))
                .locationCountry("Germany")
                .isRemote(RemoteType.REMOTE)
                .build();

        MatchScore matchScore = MatchScore.builder()
                .overallScore(90)
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(job, matchScore);

        assertThat(result.score()).isGreaterThanOrEqualTo(85);
    }

    @Test
    void shouldHandleMissingSalaryGracefully() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Backend Developer")
                .build();

        MatchScore matchScore = MatchScore.builder()
                .overallScore(75)
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(job, matchScore);

        // Salary factor should be neutral (50), not penalized
        assertThat(result.breakdown().get("salary")).isEqualTo(50);
    }

    @Test
    void shouldScoreSeniorityFromTitle() {
        JobPosting staff = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Staff Software Engineer")
                .build();

        JobPosting junior = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Junior Developer")
                .build();

        OpportunityScorer.OpportunityResult staffResult = scorer.score(staff, null);
        OpportunityScorer.OpportunityResult juniorResult = scorer.score(junior, null);

        assertThat(staffResult.breakdown().get("seniority")).isEqualTo(90);
        assertThat(juniorResult.breakdown().get("seniority")).isEqualTo(40);
    }

    @Test
    void shouldScoreLocationFitForRemote() {
        JobPosting remote = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .isRemote(RemoteType.REMOTE)
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(remote, null);
        assertThat(result.breakdown().get("locationFit")).isEqualTo(100);
    }

    @Test
    void shouldScoreLocationFitForPreferredCountry() {
        JobPosting germany = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .locationCountry("Germany")
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(germany, null);
        assertThat(result.breakdown().get("locationFit")).isEqualTo(100);
    }

    @Test
    void shouldScoreLocationFitLowerForNonPreferred() {
        JobPosting usa = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .locationCountry("USA")
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(usa, null);
        assertThat(result.breakdown().get("locationFit")).isEqualTo(30);
    }

    @Test
    void shouldReturnBreakdownWithAllFactors() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(job, null);

        assertThat(result.breakdown()).containsKeys(
                "matchScore", "interviewHistory", "salary",
                "companyQuality", "seniority", "locationFit"
        );
    }

    @Test
    void shouldScoreSalaryAboveTarget() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .salaryMax(new BigDecimal("130000"))
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(job, null);
        // 130k / 85k target = 1.53 → 100
        assertThat(result.breakdown().get("salary")).isEqualTo(100);
    }

    @Test
    void shouldScoreSalaryBelowTarget() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Engineer")
                .salaryMax(new BigDecimal("55000"))
                .build();

        OpportunityScorer.OpportunityResult result = scorer.score(job, null);
        // 55k / 85k = 0.65 → 20
        assertThat(result.breakdown().get("salary")).isEqualTo(20);
    }

    private PersonalProfile testProfile() {
        return new PersonalProfile("Sam", "Senior Backend Engineer", 8,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "language"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "expert", "framework")
                ),
                new PersonalProfile.Preferences(
                        List.of("Germany", "Netherlands", "remote"),
                        "FULL_TIME", 85000,
                        List.of("senior", "staff", "lead"),
                        List.of("English", "German"),
                        List.of()
                ),
                null, null, null
        , null);
    }
}
