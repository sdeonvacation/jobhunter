package dev.jobhunter.controller;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.dto.ApplicationDto;
import dev.jobhunter.dto.DtoMapper;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.people.ai.FunnelAnalysisTask;
import dev.jobhunter.people.dto.*;
import dev.jobhunter.people.model.enums.InterviewSource;
import dev.jobhunter.people.service.ActionScorer.ScoredAction;
import dev.jobhunter.people.service.EffectivenessTracker;
import dev.jobhunter.people.service.EffectivenessTracker.EffectivenessMetrics;
import dev.jobhunter.people.service.FunnelAggregator;
import dev.jobhunter.people.service.FunnelAggregator.FunnelData;
import dev.jobhunter.people.service.OpportunityQueue;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.service.OutcomeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
public class PipelineController {

    private final OutcomeService outcomeService;
    private final FunnelAggregator funnelAggregator;
    private final OpportunityQueue opportunityQueue;
    private final EffectivenessTracker effectivenessTracker;
    private final AiProvider aiProvider;
    private final FunnelAnalysisTask funnelAnalysisTask;
    private final OutreachContactRepository outreachContactRepository;
    private final JobPostingRepository jobPostingRepository;

    public PipelineController(OutcomeService outcomeService,
                              FunnelAggregator funnelAggregator,
                              OpportunityQueue opportunityQueue,
                              EffectivenessTracker effectivenessTracker,
                              AiProvider aiProvider,
                              FunnelAnalysisTask funnelAnalysisTask,
                              OutreachContactRepository outreachContactRepository,
                              JobPostingRepository jobPostingRepository) {
        this.outcomeService = outcomeService;
        this.funnelAggregator = funnelAggregator;
        this.opportunityQueue = opportunityQueue;
        this.effectivenessTracker = effectivenessTracker;
        this.aiProvider = aiProvider;
        this.funnelAnalysisTask = funnelAnalysisTask;
        this.outreachContactRepository = outreachContactRepository;
        this.jobPostingRepository = jobPostingRepository;
    }

    // --- Existing pipeline endpoints ---

    @GetMapping("/api/pipeline")
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

    @PostMapping("/api/pipeline/{jobId}/apply")
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

    @PutMapping("/api/pipeline/{applicationId}/outcome")
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

        Application app = result.get().getApplication();
        List<JobOutcome> outcomes = outcomeService.getOutcomes(app.getId());
        return ResponseEntity.ok(DtoMapper.toApplication(app, outcomes));
    }

    // --- Funnel endpoints ---

    @GetMapping("/api/pipeline/funnel")
    public FunnelDto getFunnel(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();

        FunnelData data = funnelAggregator.aggregate(effectiveFrom, effectiveTo);
        return toFunnelDto(data);
    }

    @GetMapping("/api/pipeline/funnel/by-source")
    public Map<String, FunnelDto> getFunnelBySource(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();

        Map<InterviewSource, FunnelData> bySource = funnelAggregator.aggregateBySource(effectiveFrom, effectiveTo);
        return bySource.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> toFunnelDto(e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    @PostMapping("/api/pipeline/analyze")
    public ResponseEntity<FunnelAnalysisDto> analyzeFunnel() {
        if (!aiProvider.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        FunnelData data = funnelAggregator.aggregate(LocalDate.now().minusDays(30), LocalDate.now());

        String systemPrompt = funnelAnalysisTask.systemPrompt(data);
        String userPrompt = funnelAnalysisTask.userPrompt(data);
        String rawResponse = aiProvider.generate(systemPrompt, userPrompt);

        FunnelAnalysisTask.FunnelAnalysis analysis = funnelAnalysisTask.parseResponse(rawResponse, data);
        FunnelAnalysisDto dto = new FunnelAnalysisDto(
                analysis.primaryBottleneck(),
                analysis.explanation(),
                analysis.suggestions(),
                analysis.stageInsights()
        );
        return ResponseEntity.ok(dto);
    }

    // --- Actions endpoint ---

    @GetMapping("/api/actions/today")
    public List<ScoredActionDto> getActionsToday(@RequestParam(defaultValue = "10") int limit) {
        List<ScoredAction> actions = opportunityQueue.getToday(limit);
        return actions.stream()
                .map(this::toScoredActionDto)
                .toList();
    }

    // --- Effectiveness endpoint ---

    @GetMapping("/api/pipeline/effectiveness")
    public EffectivenessDto getEffectiveness(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();

        Map<String, EffectivenessMetrics> byVariant = effectivenessTracker.getVariantEffectiveness(effectiveFrom, effectiveTo);
        Map<String, EffectivenessMetrics> byChannel = effectivenessTracker.getChannelEffectiveness(effectiveFrom, effectiveTo);

        return new EffectivenessDto(
                toEffectivenessDtoMap(byVariant),
                toEffectivenessDtoMap(byChannel)
        );
    }

    // --- Mapping helpers ---

    private FunnelDto toFunnelDto(FunnelData data) {
        return new FunnelDto(
                data.applications(),
                data.recruiterScreen(),
                data.technical(),
                data.finalRound(),
                data.offers(),
                data.conversionRates(),
                data.avgDaysBetweenStages()
        );
    }

    private ScoredActionDto toScoredActionDto(ScoredAction action) {
        String contactName = null;
        String companyName = null;
        String jobTitle = null;

        if (action.contactId() != null) {
            OutreachContact contact = outreachContactRepository.findById(action.contactId()).orElse(null);
            if (contact != null) {
                contactName = contact.getPersonName();
                companyName = contact.getCompany() != null ? contact.getCompany().getName() : null;
            }
        }

        if (action.jobId() != null) {
            JobPosting job = jobPostingRepository.findById(action.jobId()).orElse(null);
            if (job != null) {
                jobTitle = job.getTitle();
                if (companyName == null && job.getCompany() != null) {
                    companyName = job.getCompany().getName();
                }
            }
        }

        long daysUntilExpiry = action.urgencyScore() >= 1.2 ? 0 : Math.max(0, (long) ((1.0 - action.urgencyScore()) * 7));
        String expiresIn = daysUntilExpiry == 0 ? "overdue" : daysUntilExpiry + "d";

        return new ScoredActionDto(
                action.entityId().toString(),
                action.type().name(),
                action.actionScore(),
                action.urgencyScore(),
                action.actionScore(),
                action.type().name().toLowerCase().replace('_', ' '),
                expiresIn,
                action.contactId() != null ? action.contactId().toString() : null,
                action.jobId() != null ? action.jobId().toString() : null,
                contactName,
                companyName,
                jobTitle
        );
    }

    private Map<String, EffectivenessDto.EffectivenessMetricsDto> toEffectivenessDtoMap(
            Map<String, EffectivenessMetrics> metrics) {
        Map<String, EffectivenessDto.EffectivenessMetricsDto> result = new LinkedHashMap<>();
        for (Map.Entry<String, EffectivenessMetrics> entry : metrics.entrySet()) {
            EffectivenessMetrics m = entry.getValue();
            result.put(entry.getKey(), new EffectivenessDto.EffectivenessMetricsDto(
                    m.totalSent(),
                    m.replies(),
                    m.replyRate(),
                    m.interviewsGenerated(),
                    m.interviewConversionRate(),
                    m.sampleSize()
            ));
        }
        return result;
    }

    public record ApplyRequest(String resumeVariant, String notes) {}
    public record OutcomeRequest(String stage, String notes) {}
}
