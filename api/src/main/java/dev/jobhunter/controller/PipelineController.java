package dev.jobhunter.controller;

import dev.jobhunter.dto.ApplicationDto;
import dev.jobhunter.dto.DtoMapper;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.service.OutcomeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final OutcomeService outcomeService;

    public PipelineController(OutcomeService outcomeService) {
        this.outcomeService = outcomeService;
    }

    @GetMapping
    public List<ApplicationDto> getPipeline(@RequestParam(required = false) String status) {
        ApplicationStatus appStatus = null;
        if (status != null && !status.isBlank()) {
            appStatus = ApplicationStatus.valueOf(status.toUpperCase());
        }

        List<Application> applications = outcomeService.getPipeline(appStatus);
        return applications.stream()
                .map(app -> {
                    List<JobOutcome> outcomes = outcomeService.getOutcomes(app.getId());
                    return DtoMapper.toApplication(app, outcomes);
                })
                .toList();
    }

    @PostMapping("/{jobId}/apply")
    public ResponseEntity<ApplicationDto> markApplied(
            @PathVariable UUID jobId,
            @RequestBody(required = false) ApplyRequest request) {

        String resumeVariant = request != null ? request.resumeVariant() : null;
        String notes = request != null ? request.notes() : null;

        Optional<Application> result = outcomeService.markApplied(jobId, resumeVariant, notes);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Application app = result.get();
        List<JobOutcome> outcomes = outcomeService.getOutcomes(app.getId());
        return ResponseEntity.ok(DtoMapper.toApplication(app, outcomes));
    }

    @PutMapping("/{applicationId}/outcome")
    public ResponseEntity<ApplicationDto> recordOutcome(
            @PathVariable UUID applicationId,
            @RequestBody OutcomeRequest request) {

        if (request.stage() == null || request.stage().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        OutcomeStage stage = OutcomeStage.valueOf(request.stage().toUpperCase());
        Optional<JobOutcome> result = outcomeService.recordOutcome(applicationId, stage, request.notes());

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Return updated application
        Application app = result.get().getApplication();
        List<JobOutcome> outcomes = outcomeService.getOutcomes(app.getId());
        return ResponseEntity.ok(DtoMapper.toApplication(app, outcomes));
    }

    public record ApplyRequest(String resumeVariant, String notes) {}
    public record OutcomeRequest(String stage, String notes) {}
}
