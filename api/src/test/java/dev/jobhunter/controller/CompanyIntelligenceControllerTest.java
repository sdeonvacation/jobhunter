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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyIntelligenceControllerTest {

    @Mock
    private CompanyEnrichmentService companyEnrichmentService;
    @Mock
    private VisaSignalService visaSignalService;
    @Mock
    private HiringVelocityCalculator hiringVelocityCalculator;
    @Mock
    private CompanyRepository companyRepository;

    private CompanyIntelligenceController controller;

    @BeforeEach
    void setUp() {
        controller = new CompanyIntelligenceController(
                companyEnrichmentService, visaSignalService, hiringVelocityCalculator, companyRepository);
    }

    // --- GET /intelligence ---

    @Test
    void getIntelligence_companyNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(companyRepository.findById(id)).thenReturn(Optional.empty());

        var response = controller.getIntelligence(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getIntelligence_success_returnsMappedDto() {
        UUID id = UUID.randomUUID();
        LocalDateTime enrichedAt = LocalDateTime.of(2025, 6, 15, 10, 30, 0);

        Company company = Company.builder()
                .id(id)
                .industry("Software")
                .employeeCount(500)
                .specialties("Java, Spring Boot, Kubernetes")
                .employeeGrowth("15%")
                .fundingStage("Series B")
                .linkedinEnrichedAt(enrichedAt)
                .hasSponsoredBefore(true)
                .englishSpeaking(true)
                .internationalWorkforce(false)
                .build();

        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(hiringVelocityCalculator.calculate(id)).thenReturn(7);
        when(visaSignalService.getSignals(id)).thenReturn(new VisaSignalService.VisaSignals(
                id, true, true, false, VisaFriendliness.MEDIUM));

        var response = controller.getIntelligence(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CompanyIntelligenceDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.companyId()).isEqualTo(id.toString());
        assertThat(dto.industry()).isEqualTo("Software");
        assertThat(dto.employeeCount()).isEqualTo(500);
        assertThat(dto.specialties()).isEqualTo("Java, Spring Boot, Kubernetes");
        assertThat(dto.hiringVelocity()).isEqualTo(7);
        assertThat(dto.employeeGrowth()).isEqualTo("15%");
        assertThat(dto.fundingStage()).isEqualTo("Series B");
        assertThat(dto.lastEnrichedAt()).isEqualTo("2025-06-15T10:30:00");
        assertThat(dto.visaSignals().hasSponsoredBefore()).isTrue();
        assertThat(dto.visaSignals().englishSpeaking()).isTrue();
        assertThat(dto.visaSignals().internationalWorkforce()).isFalse();
        assertThat(dto.visaSignals().derived()).isEqualTo("MEDIUM");
    }

    @Test
    void getIntelligence_nullEnrichedAt_returnsNullInDto() {
        UUID id = UUID.randomUUID();

        Company company = Company.builder()
                .id(id)
                .industry(null)
                .linkedinEnrichedAt(null)
                .build();

        when(companyRepository.findById(id)).thenReturn(Optional.of(company));
        when(hiringVelocityCalculator.calculate(id)).thenReturn(0);
        when(visaSignalService.getSignals(id)).thenReturn(new VisaSignalService.VisaSignals(
                id, null, null, null, VisaFriendliness.UNKNOWN));

        var response = controller.getIntelligence(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CompanyIntelligenceDto dto = response.getBody();
        assertThat(dto.lastEnrichedAt()).isNull();
        assertThat(dto.industry()).isNull();
        assertThat(dto.visaSignals().derived()).isEqualTo("UNKNOWN");
    }

    // --- POST /enrich ---

    @Test
    void enrich_companyNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(companyEnrichmentService.enrich(id))
                .thenReturn(new CompanyEnrichmentResult(id, false, Set.of(), "Company not found"));

        var response = controller.enrich(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void enrich_rateLimited_returnsOkWithFailure() {
        UUID id = UUID.randomUUID();
        CompanyEnrichmentResult result = new CompanyEnrichmentResult(id, false, Set.of(), "Rate limit reached");
        when(companyEnrichmentService.enrich(id)).thenReturn(result);

        var response = controller.enrich(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().failureReason()).isEqualTo("Rate limit reached");
    }

    @Test
    void enrich_success_returnsResult() {
        UUID id = UUID.randomUUID();
        CompanyEnrichmentResult result = new CompanyEnrichmentResult(
                id, true, Set.of("industry", "specialties"), null);
        when(companyEnrichmentService.enrich(id)).thenReturn(result);

        var response = controller.enrich(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().fieldsUpdated()).containsExactlyInAnyOrder("industry", "specialties");
    }

    // --- GET /visa-signals ---

    @Test
    void getVisaSignals_companyNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(false);

        var response = controller.getVisaSignals(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getVisaSignals_success_returnsDto() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);
        when(visaSignalService.getSignals(id)).thenReturn(new VisaSignalService.VisaSignals(
                id, true, false, true, VisaFriendliness.MEDIUM));

        var response = controller.getVisaSignals(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisaSignalsDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.hasSponsoredBefore()).isTrue();
        assertThat(dto.englishSpeaking()).isFalse();
        assertThat(dto.internationalWorkforce()).isTrue();
        assertThat(dto.derived()).isEqualTo("MEDIUM");
    }

    // --- PUT /visa-signals ---

    @Test
    void updateVisaSignal_companyNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(false);

        var request = new UpdateVisaSignalRequest("hasSponsoredBefore", true);
        var response = controller.updateVisaSignal(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateVisaSignal_nullSignal_returns400() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);

        var request = new UpdateVisaSignalRequest(null, true);
        var response = controller.updateVisaSignal(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateVisaSignal_blankSignal_returns400() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);

        var request = new UpdateVisaSignalRequest("  ", false);
        var response = controller.updateVisaSignal(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateVisaSignal_unknownSignal_returns400() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);
        doThrow(new IllegalArgumentException("Unknown visa signal: bogus"))
                .when(visaSignalService).updateSignal(id, "bogus", true);

        var request = new UpdateVisaSignalRequest("bogus", true);
        var response = controller.updateVisaSignal(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateVisaSignal_success_returnsUpdatedSignals() {
        UUID id = UUID.randomUUID();
        when(companyRepository.existsById(id)).thenReturn(true);
        doNothing().when(visaSignalService).updateSignal(id, "hasSponsoredBefore", true);
        when(visaSignalService.getSignals(id)).thenReturn(new VisaSignalService.VisaSignals(
                id, true, true, true, VisaFriendliness.HIGH));

        var request = new UpdateVisaSignalRequest("hasSponsoredBefore", true);
        var response = controller.updateVisaSignal(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisaSignalsDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.hasSponsoredBefore()).isTrue();
        assertThat(dto.derived()).isEqualTo("HIGH");
        verify(visaSignalService).updateSignal(id, "hasSponsoredBefore", true);
    }
}
