package dev.jobhunter.controller;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.dto.CompanyIntelligenceDto;
import dev.jobhunter.people.dto.UpdateVisaSignalRequest;
import dev.jobhunter.people.dto.VisaSignalsDto;
import dev.jobhunter.people.model.enums.VisaFriendliness;
import dev.jobhunter.people.service.CompanyEnrichmentService;
import dev.jobhunter.people.service.CompanyEnrichmentService.CompanyEnrichmentResult;
import dev.jobhunter.people.service.HiringVelocityCalculator;
import dev.jobhunter.people.service.VisaSignalService;
import dev.jobhunter.repository.CompanyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies/{id}")
public class CompanyIntelligenceController {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final CompanyEnrichmentService companyEnrichmentService;
    private final VisaSignalService visaSignalService;
    private final HiringVelocityCalculator hiringVelocityCalculator;
    private final CompanyRepository companyRepository;

    public CompanyIntelligenceController(CompanyEnrichmentService companyEnrichmentService,
                                         VisaSignalService visaSignalService,
                                         HiringVelocityCalculator hiringVelocityCalculator,
                                         CompanyRepository companyRepository) {
        this.companyEnrichmentService = companyEnrichmentService;
        this.visaSignalService = visaSignalService;
        this.hiringVelocityCalculator = hiringVelocityCalculator;
        this.companyRepository = companyRepository;
    }

    @GetMapping("/intelligence")
    public ResponseEntity<CompanyIntelligenceDto> getIntelligence(@PathVariable UUID id) {
        var companyOpt = companyRepository.findById(id);
        if (companyOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Company company = companyOpt.get();
        int velocity = hiringVelocityCalculator.calculate(id);

        VisaSignalService.VisaSignals signals = visaSignalService.getSignals(id);
        VisaSignalsDto visaDto = toVisaSignalsDto(signals);

        CompanyIntelligenceDto dto = new CompanyIntelligenceDto(
                company.getId().toString(),
                company.getIndustry(),
                company.getEmployeeCount(),
                company.getSpecialties(),
                velocity,
                company.getEmployeeGrowth(),
                company.getFundingStage(),
                visaDto,
                company.getLinkedinEnrichedAt() != null
                        ? company.getLinkedinEnrichedAt().format(ISO_FORMAT)
                        : null
        );

        return ResponseEntity.ok(dto);
    }

    @PostMapping("/enrich")
    public ResponseEntity<CompanyEnrichmentResult> enrich(@PathVariable UUID id) {
        CompanyEnrichmentResult result = companyEnrichmentService.enrich(id);
        if (!result.success() && "Company not found".equals(result.failureReason())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/visa-signals")
    public ResponseEntity<VisaSignalsDto> getVisaSignals(@PathVariable UUID id) {
        if (!companyRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        VisaSignalService.VisaSignals signals = visaSignalService.getSignals(id);
        return ResponseEntity.ok(toVisaSignalsDto(signals));
    }

    @PutMapping("/visa-signals")
    public ResponseEntity<VisaSignalsDto> updateVisaSignal(@PathVariable UUID id,
                                                           @RequestBody UpdateVisaSignalRequest request) {
        if (!companyRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        if (request.signal() == null || request.signal().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            visaSignalService.updateSignal(id, request.signal(), request.value());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        VisaSignalService.VisaSignals updated = visaSignalService.getSignals(id);
        return ResponseEntity.ok(toVisaSignalsDto(updated));
    }

    private static VisaSignalsDto toVisaSignalsDto(VisaSignalService.VisaSignals signals) {
        return new VisaSignalsDto(
                signals.hasSponsoredBefore(),
                signals.englishSpeaking(),
                signals.internationalWorkforce(),
                signals.derived() != null ? signals.derived().name() : VisaFriendliness.UNKNOWN.name()
        );
    }
}
