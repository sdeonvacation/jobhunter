package dev.jobhunter.discovery;

import dev.jobhunter.model.Company;
import dev.jobhunter.model.DiscoveryEvent;
import dev.jobhunter.model.enums.DiscoveryOutcome;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.DiscoveryEventRepository;
import dev.jobhunter.repository.ResolutionResultRepository;
import dev.jobhunter.resolution.AtsDetector;
import dev.jobhunter.resolution.CompositeEndpointResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceAcademicBlocklistTest {

    @Mock private List<DiscoveryProvider> providers;
    @Mock private CompanyNormalizer companyNormalizer;
    @Mock private CompanyRepository companyRepository;
    @Mock private CareerEndpointRepository careerEndpointRepository;
    @Mock private DiscoveryEventRepository discoveryEventRepository;
    @Mock private ResolutionResultRepository resolutionResultRepository;
    @Mock private CompositeEndpointResolver endpointResolver;
    @Mock private AtsDetector atsDetector;

    private DiscoveryService buildService(List<String> blocklist) {
        DiscoveryProperties properties = new DiscoveryProperties(null, Map.of(), blocklist);
        return new DiscoveryService(
                providers, companyNormalizer, companyRepository,
                careerEndpointRepository, discoveryEventRepository,
                resolutionResultRepository, endpointResolver,
                atsDetector, properties
        );
    }

    @Test
    void processDiscoveredCompany_academicName_skipsAndPersistsNoCompany() {
        DiscoveryService service = buildService(List.of("(?i)fraunhofer"));

        DiscoveredCompany discovered = new DiscoveredCompany(
                "Fraunhofer Institute for Applied Information Technology",
                "Research Engineer",
                "https://example.com/job/1",
                null
        );

        DiscoveryOutcome outcome = service.processDiscoveredCompany(
                "fraunhofer institute for applied information technology", discovered);

        assertThat(outcome).isEqualTo(DiscoveryOutcome.SKIPPED_ACADEMIC);
        verify(companyRepository, never()).save(any());
        verify(careerEndpointRepository, never()).save(any());

        ArgumentCaptor<DiscoveryEvent> captor = ArgumentCaptor.forClass(DiscoveryEvent.class);
        verify(discoveryEventRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(DiscoveryOutcome.SKIPPED_ACADEMIC);
        assertThat(captor.getValue().getCompany()).isNull();
        assertThat(captor.getValue().getCompanyName())
                .isEqualTo("Fraunhofer Institute for Applied Information Technology");
    }

    @Test
    void processDiscoveredCompany_academicNormalizedName_skipsBeforeAnyLookup() {
        DiscoveryService service = buildService(List.of("(?i)university"));

        // Raw name does not match; the normalized name does
        DiscoveredCompany discovered = new DiscoveredCompany(
                "TU Muenchen",
                "PhD Position",
                "https://example.com/job/2",
                "https://www.tum.de/en/career"
        );

        DiscoveryOutcome outcome = service.processDiscoveredCompany(
                "technical university of munich", discovered);

        assertThat(outcome).isEqualTo(DiscoveryOutcome.SKIPPED_ACADEMIC);
        // Guard runs before registry lookup, endpoint detection, and registration
        verifyNoInteractions(companyRepository, careerEndpointRepository, atsDetector,
                endpointResolver, resolutionResultRepository);
        verify(discoveryEventRepository).save(any(DiscoveryEvent.class));
    }

    @Test
    void processDiscoveredCompany_normalCompany_isNotSkipped() {
        DiscoveryService service = buildService(List.of("(?i)fraunhofer"));

        Company existing = Company.builder()
                .id(UUID.randomUUID())
                .name("Acme Corp")
                .normalizedName("acme")
                .build();

        DiscoveredCompany discovered = new DiscoveredCompany(
                "Acme Corp", "Backend Engineer", "https://example.com/job/3", null);

        when(companyRepository.findByNormalizedName("acme")).thenReturn(Optional.of(existing));

        DiscoveryOutcome outcome = service.processDiscoveredCompany("acme", discovered);

        assertThat(outcome).isEqualTo(DiscoveryOutcome.ALREADY_EXISTS);
        assertThat(outcome).isNotEqualTo(DiscoveryOutcome.SKIPPED_ACADEMIC);
    }

    @Test
    void processDiscoveredCompany_malformedBlocklistPattern_treatedAsNoMatch() {
        // Broken regex must not break discovery: pattern is dropped, company flows through
        DiscoveryService service = buildService(List.of("[unterminated"));

        Company existing = Company.builder()
                .id(UUID.randomUUID())
                .name("Fraunhofer")
                .normalizedName("fraunhofer")
                .build();

        DiscoveredCompany discovered = new DiscoveredCompany(
                "Fraunhofer", "Engineer", "https://example.com/job/4", null);

        when(companyRepository.findByNormalizedName("fraunhofer")).thenReturn(Optional.of(existing));

        DiscoveryOutcome outcome = service.processDiscoveredCompany("fraunhofer", discovered);

        assertThat(outcome).isEqualTo(DiscoveryOutcome.ALREADY_EXISTS);
    }

    @Test
    void processDiscoveredCompany_nullOrBlankNames_notSkipped() {
        DiscoveryService service = buildService(List.of("(?i)fraunhofer"));

        Company existing = Company.builder()
                .id(UUID.randomUUID())
                .name("Acme")
                .normalizedName("acme")
                .build();

        DiscoveredCompany discovered = new DiscoveredCompany(
                null, "Engineer", "https://example.com/job/5", null);

        when(companyRepository.findByNormalizedName("acme")).thenReturn(Optional.of(existing));

        DiscoveryOutcome outcome = service.processDiscoveredCompany("acme", discovered);

        assertThat(outcome).isEqualTo(DiscoveryOutcome.ALREADY_EXISTS);
    }

    @Test
    void processDiscoveredCompany_nullBlocklist_defaultSafe() {
        // Defensive: null blocklist (e.g. unmocked properties) must not NPE
        DiscoveryProperties properties = new DiscoveryProperties(null, Map.of(), null);
        DiscoveryService service = new DiscoveryService(
                providers, companyNormalizer, companyRepository,
                careerEndpointRepository, discoveryEventRepository,
                resolutionResultRepository, endpointResolver,
                atsDetector, properties
        );

        Company existing = Company.builder()
                .id(UUID.randomUUID())
                .name("Acme")
                .normalizedName("acme")
                .build();

        DiscoveredCompany discovered = new DiscoveredCompany(
                "Acme", "Engineer", "https://example.com/job/6", null);

        when(companyRepository.findByNormalizedName("acme")).thenReturn(Optional.of(existing));

        assertThat(service.processDiscoveredCompany("acme", discovered))
                .isEqualTo(DiscoveryOutcome.ALREADY_EXISTS);
    }

    @Test
    void runDiscovery_skippedAcademic_countsAsAlreadyExists_keepsArity() {
        DiscoveryProvider provider = mock(DiscoveryProvider.class);
        DiscoveryProperties properties = new DiscoveryProperties(null, Map.of(), List.of("(?i)fraunhofer"));
        DiscoveryService service = new DiscoveryService(
                List.of(provider), companyNormalizer, companyRepository,
                careerEndpointRepository, discoveryEventRepository,
                resolutionResultRepository, endpointResolver,
                atsDetector, properties
        );

        when(provider.isHealthy()).thenReturn(true);
        DiscoveredCompany academic = new DiscoveredCompany(
                "Fraunhofer Gesellschaft", "Researcher", "https://example.com/job/7", null);
        when(provider.discover(any())).thenReturn(List.of(academic));
        when(companyNormalizer.normalize("Fraunhofer Gesellschaft")).thenReturn("fraunhofer gesellschaft");

        int[] result = service.runDiscovery();

        assertThat(result).hasSize(4);
        assertThat(result[0]).isEqualTo(1);  // discovered
        assertThat(result[1]).isEqualTo(0);  // registered
        assertThat(result[2]).isEqualTo(1);  // alreadyExists (skipped counted here)
        assertThat(result[3]).isEqualTo(0);  // failed
        verify(companyRepository, never()).save(any());
        verify(discoveryEventRepository).save(any(DiscoveryEvent.class));
    }
}
