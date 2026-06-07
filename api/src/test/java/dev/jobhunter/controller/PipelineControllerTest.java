package dev.jobhunter.controller;

import dev.jobhunter.dto.ApplicationDto;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.service.OutcomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {

    @Mock private OutcomeService outcomeService;

    private PipelineController controller;

    @BeforeEach
    void setUp() {
        controller = new PipelineController(outcomeService);
    }

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
}
