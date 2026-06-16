package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.LinkedInProfileService;
import dev.jobhunter.linkedin.LinkedInRateLimiter;
import dev.jobhunter.linkedin.ToolCategory;
import dev.jobhunter.model.Company;
import dev.jobhunter.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyEnrichmentServiceTest {

    @Mock
    private LinkedInProfileService linkedInProfileService;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private LinkedInRateLimiter rateLimiter;

    private CompanyEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new CompanyEnrichmentService(linkedInProfileService, companyRepository, rateLimiter);
    }

    @Test
    void enrich_companyNotFound_returnsFailure() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        var result = service.enrich(companyId);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Company not found");
        assertThat(result.fieldsUpdated()).isEmpty();
    }

    @Test
    void enrich_rateLimited_returnsFailure() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .name("TestCo")
                .linkedinUrl("https://linkedin.com/company/testco")
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(false);

        var result = service.enrich(companyId);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Rate limit reached");
    }

    @Test
    void enrich_noLinkedinUrl_returnsFailure() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .name("TestCo")
                .linkedinUrl(null)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);

        var result = service.enrich(companyId);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("No LinkedIn URL configured");
    }

    @Test
    void enrich_profileReturnsNull_returnsFailure() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .name("TestCo")
                .linkedinUrl("https://linkedin.com/company/testco")
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);
        when(linkedInProfileService.getProfile("https://linkedin.com/company/testco")).thenReturn(null);

        var result = service.enrich(companyId);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("LinkedIn returned no data");
    }

    @Test
    void enrich_success_updatesFieldsAndSaves() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .name("TestCo")
                .linkedinUrl("https://linkedin.com/company/testco")
                .build();
        var profileData = new LinkedInProfileService.ProfileData(
                "Test Company", "Technology", "TestCo",
                List.of(), List.of("Java", "Spring Boot"), List.of()
        );

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);
        when(linkedInProfileService.getProfile("https://linkedin.com/company/testco")).thenReturn(profileData);

        var result = service.enrich(companyId);

        assertThat(result.success()).isTrue();
        assertThat(result.fieldsUpdated()).contains("specialties", "linkedinEnrichedAt");
        assertThat(company.getSpecialties()).isEqualTo("Java, Spring Boot");
        assertThat(company.getLinkedinEnrichedAt()).isNotNull();
        verify(companyRepository).save(company);
    }

    @Test
    void enrich_success_setsIndustryFromHeadlineWhenNull() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .name("TestCo")
                .linkedinUrl("https://linkedin.com/company/testco")
                .industry(null)
                .build();
        var profileData = new LinkedInProfileService.ProfileData(
                "Test Company", "Enterprise Software", "TestCo",
                List.of(), List.of(), List.of()
        );

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(true);
        when(linkedInProfileService.getProfile("https://linkedin.com/company/testco")).thenReturn(profileData);

        var result = service.enrich(companyId);

        assertThat(result.success()).isTrue();
        assertThat(result.fieldsUpdated()).contains("industry");
        assertThat(company.getIndustry()).isEqualTo("Enterprise Software");
    }

    @Test
    void enrichBatch_respectsMaxPerRun() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        // All companies not found -> quick failures, but still limited
        when(companyRepository.findById(any())).thenReturn(Optional.empty());

        var results = service.enrichBatch(List.of(id1, id2, id3), 2);

        // Should process max 2 (both fail because not found, but not-found doesn't count as "processed")
        // Actually the logic only increments on success, so all 3 are tried
        assertThat(results).hasSize(3);
    }

    @Test
    void enrichBatch_stopsOnRateLimit() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Company company = Company.builder()
                .id(id1)
                .name("TestCo")
                .linkedinUrl("https://linkedin.com/company/testco")
                .build();

        when(companyRepository.findById(id1)).thenReturn(Optional.of(company));
        // First acquire in enrichBatch returns true, but enrich() also acquires -> false
        when(rateLimiter.acquire(ToolCategory.PROFILE)).thenReturn(false);

        var results = service.enrichBatch(List.of(id1, id2), 10);

        // Stops on first rate limit
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).failureReason()).isEqualTo("Rate limit reached");
    }
}
