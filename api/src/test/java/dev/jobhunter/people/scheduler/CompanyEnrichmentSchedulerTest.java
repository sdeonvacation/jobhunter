package dev.jobhunter.people.scheduler;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.service.CompanyEnrichmentService;
import dev.jobhunter.people.service.CompanyEnrichmentService.CompanyEnrichmentResult;
import dev.jobhunter.people.service.HiringVelocityCalculator;
import dev.jobhunter.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyEnrichmentSchedulerTest {

    @Mock
    private CompanyEnrichmentService enrichmentService;
    @Mock
    private HiringVelocityCalculator velocityCalculator;
    @Mock
    private CompanyRepository companyRepository;

    private CompanyEnrichmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompanyEnrichmentScheduler(enrichmentService, velocityCalculator, companyRepository, 5);
    }

    @Test
    void runWeeklyEnrichment_enrichesStaleCompaniesAndCalculatesVelocity() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .name("TestCo")
                .linkedinUrl("https://linkedin.com/company/testco")
                .linkedinEnrichedAt(null)
                .isActive(true)
                .build();

        when(companyRepository.findByIsActiveTrue()).thenReturn(List.of(company));
        when(enrichmentService.enrichBatch(anyList(), anyInt()))
                .thenReturn(List.of(new CompanyEnrichmentResult(companyId, true, Set.of("industry"), null)));
        when(velocityCalculator.calculateAll()).thenReturn(Map.of(companyId, 3));

        scheduler.runWeeklyEnrichment();

        verify(enrichmentService).enrichBatch(List.of(companyId), 5);
        verify(velocityCalculator).calculateAll();
    }

    @Test
    void runWeeklyEnrichment_skipsCompaniesWithoutLinkedinUrl() {
        Company noUrl = Company.builder()
                .id(UUID.randomUUID())
                .name("NoUrlCo")
                .linkedinUrl(null)
                .isActive(true)
                .build();
        Company recentlyEnriched = Company.builder()
                .id(UUID.randomUUID())
                .name("RecentCo")
                .linkedinUrl("https://linkedin.com/company/recentco")
                .linkedinEnrichedAt(LocalDateTime.now().minusDays(5))
                .isActive(true)
                .build();

        when(companyRepository.findByIsActiveTrue()).thenReturn(List.of(noUrl, recentlyEnriched));
        when(enrichmentService.enrichBatch(anyList(), anyInt())).thenReturn(List.of());
        when(velocityCalculator.calculateAll()).thenReturn(Map.of());

        scheduler.runWeeklyEnrichment();

        // Both filtered out: no URL, recently enriched
        verify(enrichmentService).enrichBatch(List.of(), 5);
    }

    @Test
    void runWeeklyEnrichment_enrichmentException_stillCalculatesVelocity() {
        when(companyRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(enrichmentService.enrichBatch(anyList(), anyInt()))
                .thenThrow(new RuntimeException("LinkedIn down"));
        when(velocityCalculator.calculateAll()).thenReturn(Map.of());

        scheduler.runWeeklyEnrichment();

        verify(velocityCalculator).calculateAll();
    }

    @Test
    void runWeeklyEnrichment_velocityException_doesNotPropagate() {
        when(companyRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(enrichmentService.enrichBatch(anyList(), anyInt())).thenReturn(List.of());
        when(velocityCalculator.calculateAll()).thenThrow(new RuntimeException("DB error"));

        // Should not throw
        scheduler.runWeeklyEnrichment();

        verify(enrichmentService).enrichBatch(anyList(), anyInt());
    }
}
