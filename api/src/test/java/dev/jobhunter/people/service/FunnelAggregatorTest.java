package dev.jobhunter.people.service;

import dev.jobhunter.model.Application;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.people.model.enums.InterviewSource;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.JobOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunnelAggregatorTest {

    @Mock
    private ApplicationRepository applicationRepository;
    @Mock
    private JobOutcomeRepository jobOutcomeRepository;

    private FunnelAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new FunnelAggregator(applicationRepository, jobOutcomeRepository);
    }

    @Test
    void aggregate_noApplications_returnsZeros() {
        when(applicationRepository.findAll()).thenReturn(List.of());

        FunnelAggregator.FunnelData result = aggregator.aggregate(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.applications()).isZero();
        assertThat(result.recruiterScreen()).isZero();
        assertThat(result.technical()).isZero();
        assertThat(result.finalRound()).isZero();
        assertThat(result.offers()).isZero();
        assertThat(result.conversionRates().get("application_to_screen")).isZero();
    }

    @Test
    void aggregate_withApplicationsAndOutcomes_countsCorrectly() {
        UUID appId1 = UUID.randomUUID();
        UUID appId2 = UUID.randomUUID();

        Application app1 = Application.builder()
                .id(appId1).status(ApplicationStatus.INTERVIEWING)
                .appliedDate(LocalDate.of(2024, 3, 1))
                .interviewSource(InterviewSource.APPLICATION).build();
        Application app2 = Application.builder()
                .id(appId2).status(ApplicationStatus.OFFERED)
                .appliedDate(LocalDate.of(2024, 3, 15))
                .interviewSource(InterviewSource.REFERRAL).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app1, app2));

        JobOutcome screen1 = JobOutcome.builder()
                .id(UUID.randomUUID()).application(app1)
                .stage(OutcomeStage.PHONE_SCREEN)
                .occurredAt(LocalDate.of(2024, 3, 5)).build();
        JobOutcome interview1 = JobOutcome.builder()
                .id(UUID.randomUUID()).application(app1)
                .stage(OutcomeStage.INTERVIEW_1)
                .occurredAt(LocalDate.of(2024, 3, 10)).build();
        JobOutcome screen2 = JobOutcome.builder()
                .id(UUID.randomUUID()).application(app2)
                .stage(OutcomeStage.PHONE_SCREEN)
                .occurredAt(LocalDate.of(2024, 3, 20)).build();
        JobOutcome offer2 = JobOutcome.builder()
                .id(UUID.randomUUID()).application(app2)
                .stage(OutcomeStage.OFFER)
                .occurredAt(LocalDate.of(2024, 4, 1)).build();

        when(jobOutcomeRepository.findByApplicationId(appId1)).thenReturn(List.of(screen1, interview1));
        when(jobOutcomeRepository.findByApplicationId(appId2)).thenReturn(List.of(screen2, offer2));

        FunnelAggregator.FunnelData result = aggregator.aggregate(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.applications()).isEqualTo(2);
        assertThat(result.recruiterScreen()).isEqualTo(2);
        assertThat(result.technical()).isEqualTo(1);
        assertThat(result.offers()).isEqualTo(1);
        assertThat(result.conversionRates().get("application_to_screen")).isEqualTo(1.0);
        assertThat(result.conversionRates().get("application_to_offer")).isEqualTo(0.5);
    }

    @Test
    void aggregate_filtersOutApplicationsOutsideDateRange() {
        Application outOfRange = Application.builder()
                .id(UUID.randomUUID()).status(ApplicationStatus.APPLIED)
                .appliedDate(LocalDate.of(2023, 12, 31)).build();
        Application inRange = Application.builder()
                .id(UUID.randomUUID()).status(ApplicationStatus.APPLIED)
                .appliedDate(LocalDate.of(2024, 6, 1)).build();

        when(applicationRepository.findAll()).thenReturn(List.of(outOfRange, inRange));
        when(jobOutcomeRepository.findByApplicationId(inRange.getId())).thenReturn(List.of());

        FunnelAggregator.FunnelData result = aggregator.aggregate(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.applications()).isEqualTo(1);
    }

    @Test
    void aggregate_divisionByZero_returnsZeroRate() {
        Application app = Application.builder()
                .id(UUID.randomUUID()).status(ApplicationStatus.APPLIED)
                .appliedDate(LocalDate.of(2024, 5, 1)).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app));
        when(jobOutcomeRepository.findByApplicationId(app.getId())).thenReturn(List.of());

        FunnelAggregator.FunnelData result = aggregator.aggregate(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.conversionRates().get("screen_to_technical")).isZero();
        assertThat(result.conversionRates().get("technical_to_final")).isZero();
        assertThat(result.conversionRates().get("final_to_offer")).isZero();
    }

    @Test
    void aggregateBySource_groupsByInterviewSource() {
        UUID appId1 = UUID.randomUUID();
        UUID appId2 = UUID.randomUUID();

        Application appDirect = Application.builder()
                .id(appId1).status(ApplicationStatus.APPLIED)
                .appliedDate(LocalDate.of(2024, 4, 1))
                .interviewSource(InterviewSource.APPLICATION).build();
        Application appReferral = Application.builder()
                .id(appId2).status(ApplicationStatus.OFFERED)
                .appliedDate(LocalDate.of(2024, 4, 10))
                .interviewSource(InterviewSource.REFERRAL).build();

        when(applicationRepository.findAll()).thenReturn(List.of(appDirect, appReferral));
        when(jobOutcomeRepository.findByApplicationId(appId1)).thenReturn(List.of());
        when(jobOutcomeRepository.findByApplicationId(appId2)).thenReturn(List.of());

        Map<InterviewSource, FunnelAggregator.FunnelData> result = aggregator.aggregateBySource(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).containsKeys(InterviewSource.APPLICATION, InterviewSource.REFERRAL);
        assertThat(result.get(InterviewSource.APPLICATION).applications()).isEqualTo(1);
        assertThat(result.get(InterviewSource.REFERRAL).applications()).isEqualTo(1);
    }

    @Test
    void aggregateBySource_nullSourceDefaultsToApplication() {
        Application app = Application.builder()
                .id(UUID.randomUUID()).status(ApplicationStatus.APPLIED)
                .appliedDate(LocalDate.of(2024, 5, 1))
                .interviewSource(null).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app));
        when(jobOutcomeRepository.findByApplicationId(app.getId())).thenReturn(List.of());

        Map<InterviewSource, FunnelAggregator.FunnelData> result = aggregator.aggregateBySource(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).containsKey(InterviewSource.APPLICATION);
    }

    @Test
    void aggregate_avgDaysBetweenStages_computesCorrectly() {
        UUID appId = UUID.randomUUID();
        Application app = Application.builder()
                .id(appId).status(ApplicationStatus.INTERVIEWING)
                .appliedDate(LocalDate.of(2024, 3, 1)).build();

        JobOutcome screen = JobOutcome.builder()
                .id(UUID.randomUUID()).application(app)
                .stage(OutcomeStage.PHONE_SCREEN)
                .occurredAt(LocalDate.of(2024, 3, 8)).build();
        JobOutcome tech = JobOutcome.builder()
                .id(UUID.randomUUID()).application(app)
                .stage(OutcomeStage.INTERVIEW_1)
                .occurredAt(LocalDate.of(2024, 3, 15)).build();

        when(applicationRepository.findAll()).thenReturn(List.of(app));
        when(jobOutcomeRepository.findByApplicationId(appId)).thenReturn(List.of(screen, tech));

        FunnelAggregator.FunnelData result = aggregator.aggregate(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.avgDaysBetweenStages().get("application_to_screen")).isEqualTo(7.0);
        assertThat(result.avgDaysBetweenStages().get("screen_to_technical")).isEqualTo(7.0);
    }
}
