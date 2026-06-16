package dev.jobhunter.controller;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.ApplicationDto;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.people.ai.FunnelAnalysisTask;
import dev.jobhunter.people.ai.FunnelAnalysisTask.FunnelAnalysis;
import dev.jobhunter.people.dto.*;
import dev.jobhunter.people.model.enums.InterviewSource;
import dev.jobhunter.people.service.ActionScorer.ActionType;
import dev.jobhunter.people.service.ActionScorer.ScoredAction;
import dev.jobhunter.people.service.EffectivenessTracker;
import dev.jobhunter.people.service.EffectivenessTracker.EffectivenessMetrics;
import dev.jobhunter.people.service.FunnelAggregator;
import dev.jobhunter.people.service.FunnelAggregator.FunnelData;
import dev.jobhunter.people.service.OpportunityQueue;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.service.OutcomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {

    @Mock private OutcomeService outcomeService;
    @Mock private FunnelAggregator funnelAggregator;
    @Mock private OpportunityQueue opportunityQueue;
    @Mock private EffectivenessTracker effectivenessTracker;
    @Mock private AiProvider aiProvider;
    @Mock private FunnelAnalysisTask funnelAnalysisTask;
    @Mock private OutreachContactRepository outreachContactRepository;
    @Mock private JobPostingRepository jobPostingRepository;

    private PipelineController controller;

    @BeforeEach
    void setUp() {
        controller = new PipelineController(
                outcomeService,
                funnelAggregator,
                opportunityQueue,
                effectivenessTracker,
                aiProvider,
                funnelAnalysisTask,
                outreachContactRepository,
                jobPostingRepository
        );
    }

    // --- Existing endpoint tests ---

    @Test
    void getPipeline_noFilter_returnsAll() {
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().name("Acme").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").company(company).build();

        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.APPLIED);
        app.setAppliedDate(LocalDate.now());
        app.setCreatedAt(LocalDateTime.now());

        when(outcomeService.getPipeline(null)).thenReturn(List.of(app));
        when(outcomeService.getOutcomes(appId)).thenReturn(List.of());

        List<ApplicationDto> result = controller.getPipeline(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).jobTitle()).isEqualTo("Dev");
        assertThat(result.get(0).status()).isEqualTo("APPLIED");
    }

    @Test
    void markApplied_jobNotFound_returns404() {
        UUID jobId = UUID.randomUUID();
        when(outcomeService.markApplied(eq(jobId), any(), any())).thenReturn(Optional.empty());

        var response = controller.markApplied(jobId, new PipelineController.ApplyRequest("v1", "notes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void markApplied_success_returnsApplication() {
        UUID jobId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        Company company = Company.builder().name("Co").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Eng").company(company).build();

        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.APPLIED);
        app.setAppliedDate(LocalDate.now());
        app.setCreatedAt(LocalDateTime.now());

        when(outcomeService.markApplied(jobId, "tailored", "test")).thenReturn(Optional.of(app));
        when(outcomeService.getOutcomes(appId)).thenReturn(List.of());

        var response = controller.markApplied(jobId, new PipelineController.ApplyRequest("tailored", "test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().jobTitle()).isEqualTo("Eng");
    }

    @Test
    void recordOutcome_blankStage_returnsBadRequest() {
        UUID appId = UUID.randomUUID();

        var response = controller.recordOutcome(appId, new PipelineController.OutcomeRequest("", "notes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void recordOutcome_notFound_returns404() {
        UUID appId = UUID.randomUUID();
        when(outcomeService.recordOutcome(appId, OutcomeStage.INTERVIEW_1, "good"))
                .thenReturn(Optional.empty());

        var response = controller.recordOutcome(appId, new PipelineController.OutcomeRequest("INTERVIEW_1", "good"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recordOutcome_success_returnsUpdatedApplication() {
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().name("X").build();
        JobPosting job = JobPosting.builder().id(jobId).title("SWE").company(company).build();

        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.INTERVIEWING);
        app.setAppliedDate(LocalDate.now());
        app.setCreatedAt(LocalDateTime.now());

        JobOutcome outcome = new JobOutcome();
        outcome.setId(UUID.randomUUID());
        outcome.setApplication(app);
        outcome.setStage(OutcomeStage.INTERVIEW_1);
        outcome.setOccurredAt(LocalDate.now());

        when(outcomeService.recordOutcome(appId, OutcomeStage.INTERVIEW_1, "went well"))
                .thenReturn(Optional.of(outcome));
        when(outcomeService.getOutcomes(appId)).thenReturn(List.of(outcome));

        var response = controller.recordOutcome(appId,
                new PipelineController.OutcomeRequest("INTERVIEW_1", "went well"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("INTERVIEWING");
        assertThat(response.getBody().outcomes()).hasSize(1);
    }

    // --- Funnel endpoint tests ---

    @Test
    void getFunnel_defaultsToLast30Days() {
        FunnelData data = new FunnelData(10, 5, 3, 2, 1,
                Map.of("application_to_screen", 0.5),
                Map.of("application_to_screen", 7.0));

        when(funnelAggregator.aggregate(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(data);

        FunnelDto result = controller.getFunnel(null, null);

        assertThat(result.applications()).isEqualTo(10);
        assertThat(result.recruiterScreen()).isEqualTo(5);
        assertThat(result.technical()).isEqualTo(3);
        assertThat(result.finalRound()).isEqualTo(2);
        assertThat(result.offers()).isEqualTo(1);
        assertThat(result.conversionRates()).containsEntry("application_to_screen", 0.5);
        verify(funnelAggregator).aggregate(LocalDate.now().minusDays(30), LocalDate.now());
    }

    @Test
    void getFunnel_customDateRange() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);
        FunnelData data = new FunnelData(20, 8, 4, 2, 1, Map.of(), Map.of());

        when(funnelAggregator.aggregate(from, to)).thenReturn(data);

        FunnelDto result = controller.getFunnel(from, to);

        assertThat(result.applications()).isEqualTo(20);
        verify(funnelAggregator).aggregate(from, to);
    }

    @Test
    void getFunnelBySource_groupsByInterviewSource() {
        FunnelData appData = new FunnelData(10, 5, 3, 1, 0, Map.of(), Map.of());
        FunnelData referralData = new FunnelData(3, 3, 2, 2, 1, Map.of(), Map.of());

        Map<InterviewSource, FunnelData> bySource = new EnumMap<>(InterviewSource.class);
        bySource.put(InterviewSource.APPLICATION, appData);
        bySource.put(InterviewSource.REFERRAL, referralData);

        when(funnelAggregator.aggregateBySource(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(bySource);

        Map<String, FunnelDto> result = controller.getFunnelBySource(null, null);

        assertThat(result).containsKey("APPLICATION");
        assertThat(result).containsKey("REFERRAL");
        assertThat(result.get("APPLICATION").applications()).isEqualTo(10);
        assertThat(result.get("REFERRAL").offers()).isEqualTo(1);
    }

    // --- Analyze endpoint tests ---

    @Test
    void analyzeFunnel_aiUnavailable_returns503() {
        when(aiProvider.isAvailable()).thenReturn(false);

        ResponseEntity<FunnelAnalysisDto> response = controller.analyzeFunnel();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(funnelAggregator);
    }

    @Test
    void analyzeFunnel_success_returnsAnalysis() {
        FunnelData data = new FunnelData(50, 10, 5, 2, 0, Map.of(), Map.of());
        FunnelAnalysis analysis = new FunnelAnalysis(
                "top-of-funnel",
                "Low screen rate indicates resume issues",
                List.of("Tailor resume keywords", "Apply to better-fit roles"),
                Map.of("application_to_screen", "20% is below average")
        );

        when(aiProvider.isAvailable()).thenReturn(true);
        when(funnelAggregator.aggregate(any(LocalDate.class), any(LocalDate.class))).thenReturn(data);
        when(funnelAnalysisTask.systemPrompt(data)).thenReturn("system");
        when(funnelAnalysisTask.userPrompt(data)).thenReturn("user");
        when(aiProvider.generate("system", "user")).thenReturn("{\"primaryBottleneck\":\"top-of-funnel\"}");
        when(funnelAnalysisTask.parseResponse(any(), eq(data))).thenReturn(analysis);

        ResponseEntity<FunnelAnalysisDto> response = controller.analyzeFunnel();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        FunnelAnalysisDto dto = response.getBody();
        assertThat(dto.primaryBottleneck()).isEqualTo("top-of-funnel");
        assertThat(dto.explanation()).contains("Low screen rate");
        assertThat(dto.suggestions()).hasSize(2);
        assertThat(dto.stageInsights()).containsKey("application_to_screen");
    }

    // --- Actions endpoint tests ---

    @Test
    void getActionsToday_defaultLimit() {
        UUID contactId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Company company = Company.builder().name("TechCo").build();
        OutreachContact contact = new OutreachContact();
        contact.setId(contactId);
        contact.setPersonName("Jane Doe");
        contact.setCompany(company);

        ScoredAction action = new ScoredAction(entityId, ActionType.FOLLOW_UP, contactId, null, 0.8, 0.64);

        when(opportunityQueue.getToday(10)).thenReturn(List.of(action));
        when(outreachContactRepository.findById(contactId)).thenReturn(Optional.of(contact));

        List<ScoredActionDto> result = controller.getActionsToday(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entityId()).isEqualTo(entityId.toString());
        assertThat(result.get(0).type()).isEqualTo("FOLLOW_UP");
        assertThat(result.get(0).contactName()).isEqualTo("Jane Doe");
        assertThat(result.get(0).companyName()).isEqualTo("TechCo");
    }

    @Test
    void getActionsToday_withJobId_enrichesJobTitle() {
        UUID jobId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Company company = Company.builder().name("BigCorp").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Senior SWE").company(company).build();

        ScoredAction action = new ScoredAction(entityId, ActionType.FOLLOW_UP, null, jobId, 1.2, 0.6);

        when(opportunityQueue.getToday(5)).thenReturn(List.of(action));
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        List<ScoredActionDto> result = controller.getActionsToday(5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).jobTitle()).isEqualTo("Senior SWE");
        assertThat(result.get(0).companyName()).isEqualTo("BigCorp");
        assertThat(result.get(0).expiresIn()).isEqualTo("overdue");
    }

    @Test
    void getActionsToday_emptyQueue_returnsEmptyList() {
        when(opportunityQueue.getToday(10)).thenReturn(List.of());

        List<ScoredActionDto> result = controller.getActionsToday(10);

        assertThat(result).isEmpty();
    }

    @Test
    void getActionsToday_contactNotFound_nullNames() {
        UUID entityId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ScoredAction action = new ScoredAction(entityId, ActionType.CONNECT, contactId, null, 0.5, 0.3);

        when(opportunityQueue.getToday(10)).thenReturn(List.of(action));
        when(outreachContactRepository.findById(contactId)).thenReturn(Optional.empty());

        List<ScoredActionDto> result = controller.getActionsToday(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).contactName()).isNull();
        assertThat(result.get(0).companyName()).isNull();
    }

    // --- Effectiveness endpoint tests ---

    @Test
    void getEffectiveness_returnsCombinedMetrics() {
        EffectivenessMetrics variantMetrics = new EffectivenessMetrics(20, 8, 0.4, 3, 0.15, 20);
        EffectivenessMetrics channelMetrics = new EffectivenessMetrics(15, 5, 0.333, 2, 0.133, 15);

        when(effectivenessTracker.getVariantEffectiveness(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Map.of("intro_v1", variantMetrics));
        when(effectivenessTracker.getChannelEffectiveness(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Map.of("LINKEDIN", channelMetrics));

        EffectivenessDto result = controller.getEffectiveness(null, null);

        assertThat(result.byVariant()).containsKey("intro_v1");
        assertThat(result.byVariant().get("intro_v1").totalSent()).isEqualTo(20);
        assertThat(result.byVariant().get("intro_v1").replyRate()).isEqualTo(0.4);

        assertThat(result.byChannel()).containsKey("LINKEDIN");
        assertThat(result.byChannel().get("LINKEDIN").totalSent()).isEqualTo(15);
        assertThat(result.byChannel().get("LINKEDIN").replies()).isEqualTo(5);
    }

    @Test
    void getEffectiveness_customDateRange() {
        LocalDate from = LocalDate.of(2025, 3, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);

        when(effectivenessTracker.getVariantEffectiveness(from, to)).thenReturn(Map.of());
        when(effectivenessTracker.getChannelEffectiveness(from, to)).thenReturn(Map.of());

        EffectivenessDto result = controller.getEffectiveness(from, to);

        assertThat(result.byVariant()).isEmpty();
        assertThat(result.byChannel()).isEmpty();
        verify(effectivenessTracker).getVariantEffectiveness(from, to);
        verify(effectivenessTracker).getChannelEffectiveness(from, to);
    }
}
