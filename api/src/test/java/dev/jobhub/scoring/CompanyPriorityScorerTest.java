package dev.jobhub.scoring;

import dev.jobhub.model.Company;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyPriorityScorerTest {

    @Mock
    private PersonalProfileLoader profileLoader;

    private CompanyPriorityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new CompanyPriorityScorer(profileLoader);
        lenient().when(profileLoader.getProfile()).thenReturn(testProfile());
    }

    @Test
    void shouldReturnNeutralForNewCompany() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("New Startup")
                .interviewRate(0.0)
                .totalApplications(0)
                .build();

        double score = scorer.score(company);

        // All neutral → around 50
        assertThat(score).isBetween(40.0, 60.0);
    }

    @Test
    void shouldScoreHighForWellPerformingCompany() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Great Corp")
                .interviewRate(0.8)
                .avgMatchScore(85)
                .totalApplications(15)
                .country("Germany")
                .updatedAt(LocalDateTime.now().minusDays(3))
                .build();

        double score = scorer.score(company);

        assertThat(score).isGreaterThanOrEqualTo(75.0);
    }

    @Test
    void shouldWeightInterviewRateHeavily() {
        Company highInterview = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.9)
                .totalApplications(0)
                .build();

        Company lowInterview = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.1)
                .totalApplications(0)
                .build();

        double highScore = scorer.score(highInterview);
        double lowScore = scorer.score(lowInterview);

        assertThat(highScore).isGreaterThan(lowScore);
        // Difference should be significant since interview_rate weight is 0.35
        assertThat(highScore - lowScore).isGreaterThan(20.0);
    }

    @Test
    void shouldConsiderRecency() {
        Company recent = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.0)
                .totalApplications(0)
                .updatedAt(LocalDateTime.now().minusDays(2))
                .build();

        Company stale = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.0)
                .totalApplications(0)
                .updatedAt(LocalDateTime.now().minusDays(120))
                .build();

        double recentScore = scorer.score(recent);
        double staleScore = scorer.score(stale);

        assertThat(recentScore).isGreaterThan(staleScore);
    }

    @Test
    void shouldConsiderLocationFit() {
        Company germany = Company.builder()
                .id(UUID.randomUUID())
                .country("Germany")
                .interviewRate(0.0)
                .totalApplications(0)
                .build();

        Company us = Company.builder()
                .id(UUID.randomUUID())
                .country("United States")
                .interviewRate(0.0)
                .totalApplications(0)
                .build();

        double germanyScore = scorer.score(germany);
        double usScore = scorer.score(us);

        assertThat(germanyScore).isGreaterThan(usScore);
    }

    @Test
    void shouldScoreHiringVolume() {
        Company active = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.0)
                .totalApplications(25)
                .build();

        Company inactive = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(0.0)
                .totalApplications(1)
                .build();

        double activeScore = scorer.score(active);
        double inactiveScore = scorer.score(inactive);

        assertThat(activeScore).isGreaterThan(inactiveScore);
    }

    @Test
    void shouldClampScoreBetween0And100() {
        // Maximum: all factors at 100
        Company best = Company.builder()
                .id(UUID.randomUUID())
                .interviewRate(1.0)
                .avgMatchScore(100)
                .totalApplications(50)
                .country("Germany")
                .updatedAt(LocalDateTime.now())
                .build();

        double score = scorer.score(best);
        assertThat(score).isLessThanOrEqualTo(100.0);
        assertThat(score).isGreaterThanOrEqualTo(0.0);
    }

    private PersonalProfile testProfile() {
        return new PersonalProfile("Sam", "Senior Backend Engineer", 8,
                List.of(),
                new PersonalProfile.Preferences(
                        List.of("Germany", "Netherlands", "remote"),
                        "FULL_TIME", 85000,
                        List.of("senior", "staff"),
                        List.of("English", "German"),
                        List.of()
                ),
                null, null, null
        );
    }
}
