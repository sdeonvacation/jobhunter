package dev.jobhunter.service;

import dev.jobhunter.dto.FunnelMetricsDto;
import dev.jobhunter.dto.PatternAnalyticsDto;
import dev.jobhunter.model.*;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.model.enums.RemoteType;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.JobOutcomeRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatternAnalysisServiceTest {

    @Mock private ApplicationRepository applicationRepository;
    @Mock private JobOutcomeRepository jobOutcomeRepository;
    @Mock private MatchScoreRepository matchScoreRepository;

    private PatternAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new PatternAnalysisService(
                applicationRepository, jobOutcomeRepository, matchScoreRepository);
    }

    @Test
    void analyzePatterns_noApplications_returnsEmptyMetrics() {
        when(applicationRepository.findAll()).thenReturn(List.of());
        when(matchScoreRepository.count()).thenReturn(50L);

        PatternAnalyticsDto result = service.analyzePatterns(LocalDate.now().minusDays(30));

        assertThat(result.funnel().totalEvaluated()).isEqualTo(50);
        assertThat(result.funnel().applied()).isZero();
        assertThat(result.funnel().responded()).isZero();
        assertThat(result.funnel().applicationRate()).isZero(); // 0/50 = 0
        assertThat(result.scoreComparison().positiveCount()).isZero();
        assertThat(result.scoreComparison().negativeCount()).isZero();
        assertThat(result.blockerAnalysis()).isEmpty();
        assertThat(result.techStackGaps()).isEmpty();
        assertThat(result.scoreThreshold()).isZero();
        assertThat(result.archetypeByCompany()).isEmpty();
        assertThat(result.archetypeByRemoteType()).isEmpty();
    }

    @Test
    void analyzePatterns_filtersApplicationsByDate() {
        LocalDate since = LocalDate.of(2024, 6, 1);
        Application recent = buildApplication(ApplicationStatus.APPLIED, LocalDate.of(2024, 6, 15));
        Application old = buildApplication(ApplicationStatus.APPLIED, LocalDate.of(2024, 5, 1));

        when(applicationRepository.findAll()).thenReturn(List.of(recent, old));
        when(matchScoreRepository.count()).thenReturn(100L);

        PatternAnalyticsDto result = service.analyzePatterns(since);

        // Only the recent application should be counted
        assertThat(result.funnel().applied()).isEqualTo(1);
    }

    @Test
    void computeFunnel_countsStatusesCorrectly() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        Application applied = buildApplication(ApplicationStatus.APPLIED, LocalDate.of(2024, 2, 1));
        Application phoneScreen = buildApplication(ApplicationStatus.PHONE_SCREEN, LocalDate.of(2024, 2, 2));
        Application interviewing = buildApplication(ApplicationStatus.INTERVIEWING, LocalDate.of(2024, 2, 3));
        Application offered = buildApplication(ApplicationStatus.OFFERED, LocalDate.of(2024, 2, 4));
        Application rejected = buildApplication(ApplicationStatus.REJECTED, LocalDate.of(2024, 2, 5));

        when(applicationRepository.findAll()).thenReturn(
                List.of(applied, phoneScreen, interviewing, offered, rejected));
        when(matchScoreRepository.count()).thenReturn(200L);

        PatternAnalyticsDto result = service.analyzePatterns(since);
        FunnelMetricsDto funnel = result.funnel();

        assertThat(funnel.totalEvaluated()).isEqualTo(200);
        assertThat(funnel.applied()).isEqualTo(5);
        // responded: phone_screen(1) + interviewing(1) + offered(1) = 3
        assertThat(funnel.responded()).isEqualTo(3);
        // interviewing: interviewing(1) + offered(1) = 2
        assertThat(funnel.interviewing()).isEqualTo(2);
        assertThat(funnel.offered()).isEqualTo(1);
        assertThat(funnel.rejected()).isEqualTo(1);
    }

    @Test
    void computeFunnel_calculatesRates() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        Application interviewing = buildApplication(ApplicationStatus.INTERVIEWING, LocalDate.of(2024, 2, 1));
        Application rejected = buildApplication(ApplicationStatus.REJECTED, LocalDate.of(2024, 2, 2));

        when(applicationRepository.findAll()).thenReturn(List.of(interviewing, rejected));
        when(matchScoreRepository.count()).thenReturn(10L);

        PatternAnalyticsDto result = service.analyzePatterns(since);
        FunnelMetricsDto funnel = result.funnel();

        // applicationRate: 2/10 = 0.2
        assertThat(funnel.applicationRate()).isEqualTo(0.2);
        // responseRate: 1/2 = 0.5 (only INTERVIEWING counts as responded)
        assertThat(funnel.responseRate()).isEqualTo(0.5);
        // interviewRate: 1/1 = 1.0
        assertThat(funnel.interviewRate()).isEqualTo(1.0);
    }

    @Test
    void scoreComparison_separatesPositiveAndNegativeOutcomes() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        Application positive = buildApplicationWithJob(ApplicationStatus.INTERVIEWING, since.plusDays(1), jobId1);
        Application negative = buildApplicationWithJob(ApplicationStatus.REJECTED, since.plusDays(2), jobId2);

        MatchScore score1 = MatchScore.builder().overallScore(85).build();
        MatchScore score2 = MatchScore.builder().overallScore(45).build();

        when(applicationRepository.findAll()).thenReturn(List.of(positive, negative));
        when(matchScoreRepository.count()).thenReturn(100L);
        when(matchScoreRepository.findByJobId(jobId1)).thenReturn(Optional.of(score1));
        when(matchScoreRepository.findByJobId(jobId2)).thenReturn(Optional.of(score2));

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.scoreComparison().avgScorePositiveOutcome()).isEqualTo(85.0);
        assertThat(result.scoreComparison().avgScoreNegativeOutcome()).isEqualTo(45.0);
        assertThat(result.scoreComparison().positiveCount()).isEqualTo(1);
        assertThat(result.scoreComparison().negativeCount()).isEqualTo(1);
    }

    @Test
    void blockerAnalysis_groupsRejectionReasons() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        UUID appId1 = UUID.randomUUID();
        UUID appId2 = UUID.randomUUID();

        Application rejected1 = buildApplicationWithId(ApplicationStatus.REJECTED, since.plusDays(1), appId1);
        Application rejected2 = buildApplicationWithId(ApplicationStatus.REJECTED, since.plusDays(2), appId2);

        JobOutcome outcome1 = JobOutcome.builder()
                .stage(OutcomeStage.REJECTED).notes("Not enough experience").build();
        JobOutcome outcome2 = JobOutcome.builder()
                .stage(OutcomeStage.REJECTED).notes("not enough experience").build();

        when(applicationRepository.findAll()).thenReturn(List.of(rejected1, rejected2));
        when(matchScoreRepository.count()).thenReturn(50L);
        when(jobOutcomeRepository.findByApplicationId(appId1)).thenReturn(List.of(outcome1));
        when(jobOutcomeRepository.findByApplicationId(appId2)).thenReturn(List.of(outcome2));

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.blockerAnalysis()).hasSize(1);
        assertThat(result.blockerAnalysis().get(0).reason()).isEqualTo("not enough experience");
        assertThat(result.blockerAnalysis().get(0).count()).isEqualTo(2);
    }

    @Test
    void blockerAnalysis_ignoresNullAndBlankNotes() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        UUID appId = UUID.randomUUID();

        Application rejected = buildApplicationWithId(ApplicationStatus.REJECTED, since.plusDays(1), appId);

        JobOutcome outcomeNoNotes = JobOutcome.builder()
                .stage(OutcomeStage.REJECTED).notes(null).build();
        JobOutcome outcomeBlank = JobOutcome.builder()
                .stage(OutcomeStage.REJECTED).notes("  ").build();

        when(applicationRepository.findAll()).thenReturn(List.of(rejected));
        when(matchScoreRepository.count()).thenReturn(10L);
        when(jobOutcomeRepository.findByApplicationId(appId)).thenReturn(List.of(outcomeNoNotes, outcomeBlank));

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.blockerAnalysis()).isEmpty();
    }

    @Test
    void techStackGaps_aggregatesMissingSkills() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        Application app1 = buildApplicationWithJob(ApplicationStatus.APPLIED, since.plusDays(1), jobId1);
        Application app2 = buildApplicationWithJob(ApplicationStatus.APPLIED, since.plusDays(2), jobId2);

        MatchScore score1 = MatchScore.builder().missingSkills(List.of("Kubernetes", "Terraform")).build();
        MatchScore score2 = MatchScore.builder().missingSkills(List.of("kubernetes", "Go")).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app1, app2));
        when(matchScoreRepository.count()).thenReturn(100L);
        when(matchScoreRepository.findByJobId(jobId1)).thenReturn(Optional.of(score1));
        when(matchScoreRepository.findByJobId(jobId2)).thenReturn(Optional.of(score2));

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.techStackGaps()).hasSizeGreaterThanOrEqualTo(2);
        // "kubernetes" should appear twice (case-insensitive merge)
        assertThat(result.techStackGaps().get(0).skill()).isEqualTo("kubernetes");
        assertThat(result.techStackGaps().get(0).occurrences()).isEqualTo(2);
    }

    @Test
    void techStackGaps_limitsTo15() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        UUID jobId = UUID.randomUUID();

        Application app = buildApplicationWithJob(ApplicationStatus.APPLIED, since.plusDays(1), jobId);

        List<String> manySkills = List.of(
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j",
                "k", "l", "m", "n", "o", "p", "q", "r", "s", "t");
        MatchScore score = MatchScore.builder().missingSkills(manySkills).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app));
        when(matchScoreRepository.count()).thenReturn(50L);
        when(matchScoreRepository.findByJobId(jobId)).thenReturn(Optional.of(score));

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.techStackGaps()).hasSize(15);
    }

    @Test
    void scoreThreshold_returnsMinScoreOfPositiveOutcomes() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        Application app1 = buildApplicationWithJob(ApplicationStatus.INTERVIEWING, since.plusDays(1), jobId1);
        Application app2 = buildApplicationWithJob(ApplicationStatus.OFFERED, since.plusDays(2), jobId2);

        MatchScore score1 = MatchScore.builder().overallScore(72).build();
        MatchScore score2 = MatchScore.builder().overallScore(88).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app1, app2));
        when(matchScoreRepository.count()).thenReturn(100L);
        when(matchScoreRepository.findByJobId(jobId1)).thenReturn(Optional.of(score1));
        when(matchScoreRepository.findByJobId(jobId2)).thenReturn(Optional.of(score2));

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.scoreThreshold()).isEqualTo(72);
    }

    @Test
    void scoreThreshold_returnsZeroWhenNoPositiveOutcomes() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        Application rejected = buildApplication(ApplicationStatus.REJECTED, since.plusDays(1));

        when(applicationRepository.findAll()).thenReturn(List.of(rejected));
        when(matchScoreRepository.count()).thenReturn(10L);

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.scoreThreshold()).isZero();
    }

    @Test
    void archetypeByCompany_groupsByCompanyName() {
        LocalDate since = LocalDate.of(2024, 1, 1);
        Company companyA = Company.builder().id(UUID.randomUUID()).name("Acme Corp").build();
        Company companyB = Company.builder().id(UUID.randomUUID()).name("Beta Inc").build();

        Application app1 = buildApplicationWithCompany(since.plusDays(1), companyA);
        Application app2 = buildApplicationWithCompany(since.plusDays(2), companyA);
        Application app3 = buildApplicationWithCompany(since.plusDays(3), companyB);

        when(applicationRepository.findAll()).thenReturn(List.of(app1, app2, app3));
        when(matchScoreRepository.count()).thenReturn(100L);

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.archetypeByCompany()).containsEntry("Acme Corp", 2);
        assertThat(result.archetypeByCompany()).containsEntry("Beta Inc", 1);
    }

    @Test
    void archetypeByRemoteType_groupsByRemoteType() {
        LocalDate since = LocalDate.of(2024, 1, 1);

        Application remote1 = buildApplicationWithRemoteType(since.plusDays(1), RemoteType.REMOTE);
        Application remote2 = buildApplicationWithRemoteType(since.plusDays(2), RemoteType.REMOTE);
        Application hybrid = buildApplicationWithRemoteType(since.plusDays(3), RemoteType.HYBRID);
        Application unknown = buildApplicationWithRemoteType(since.plusDays(4), null);

        when(applicationRepository.findAll()).thenReturn(List.of(remote1, remote2, hybrid, unknown));
        when(matchScoreRepository.count()).thenReturn(100L);

        PatternAnalyticsDto result = service.analyzePatterns(since);

        assertThat(result.archetypeByRemoteType()).containsEntry("REMOTE", 2);
        assertThat(result.archetypeByRemoteType()).containsEntry("HYBRID", 1);
        assertThat(result.archetypeByRemoteType()).containsEntry("UNKNOWN", 1);
    }

    @Test
    void analyzePatterns_zeroEvaluated_ratesAreZero() {
        when(applicationRepository.findAll()).thenReturn(List.of());
        when(matchScoreRepository.count()).thenReturn(0L);

        PatternAnalyticsDto result = service.analyzePatterns(LocalDate.now().minusDays(30));

        assertThat(result.funnel().applicationRate()).isEqualTo(0.0);
        assertThat(result.funnel().responseRate()).isEqualTo(0.0);
        assertThat(result.funnel().interviewRate()).isEqualTo(0.0);
        assertThat(result.funnel().offerRate()).isEqualTo(0.0);
    }

    // --- Helpers ---

    private Application buildApplication(ApplicationStatus status, LocalDate appliedDate) {
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();
        return Application.builder()
                .id(UUID.randomUUID())
                .job(job)
                .status(status)
                .appliedDate(appliedDate)
                .build();
    }

    private Application buildApplicationWithJob(ApplicationStatus status, LocalDate appliedDate, UUID jobId) {
        JobPosting job = JobPosting.builder().id(jobId).build();
        return Application.builder()
                .id(UUID.randomUUID())
                .job(job)
                .status(status)
                .appliedDate(appliedDate)
                .build();
    }

    private Application buildApplicationWithId(ApplicationStatus status, LocalDate appliedDate, UUID appId) {
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();
        return Application.builder()
                .id(appId)
                .job(job)
                .status(status)
                .appliedDate(appliedDate)
                .build();
    }

    private Application buildApplicationWithCompany(LocalDate appliedDate, Company company) {
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).company(company).build();
        return Application.builder()
                .id(UUID.randomUUID())
                .job(job)
                .status(ApplicationStatus.APPLIED)
                .appliedDate(appliedDate)
                .build();
    }

    private Application buildApplicationWithRemoteType(LocalDate appliedDate, RemoteType remoteType) {
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).isRemote(remoteType).build();
        return Application.builder()
                .id(UUID.randomUUID())
                .job(job)
                .status(ApplicationStatus.APPLIED)
                .appliedDate(appliedDate)
                .build();
    }
}
