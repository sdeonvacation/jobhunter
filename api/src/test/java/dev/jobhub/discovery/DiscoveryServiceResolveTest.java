package dev.jobhub.discovery;

import dev.jobhub.model.Company;
import dev.jobhub.model.enums.CompanyStatus;
import dev.jobhub.model.enums.Confidence;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.DiscoveryEventRepository;
import dev.jobhub.repository.ResolutionResultRepository;
import dev.jobhub.resolution.AtsDetector;
import dev.jobhub.resolution.CompositeEndpointResolver;
import dev.jobhub.resolution.ResolutionResultDto;
import dev.jobhub.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceResolveTest {

    @Mock private List<DiscoveryProvider> providers;
    @Mock private CompanyNormalizer companyNormalizer;
    @Mock private CompanyRepository companyRepository;
    @Mock private CareerEndpointRepository careerEndpointRepository;
    @Mock private DiscoveryEventRepository discoveryEventRepository;
    @Mock private ResolutionResultRepository resolutionResultRepository;
    @Mock private CompositeEndpointResolver endpointResolver;
    @Mock private AtsDetector atsDetector;
    @Mock private DiscoveryProperties properties;

    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = new DiscoveryService(
                providers, companyNormalizer, companyRepository,
                careerEndpointRepository, discoveryEventRepository,
                resolutionResultRepository, endpointResolver,
                atsDetector, properties
        );
    }

    @Test
    void resolveDiscoveredCompanies_noLimit_usesUnpaged() {
        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), eq(Pageable.unpaged())))
                .thenReturn(Collections.emptyList());

        int[] stats = discoveryService.resolveDiscoveredCompanies(null);

        assertThat(stats).containsExactly(0, 0, 0, 0);
        verify(companyRepository).findByStatusAndNoEndpoints(CompanyStatus.DISCOVERED, Pageable.unpaged());
    }

    @Test
    void resolveDiscoveredCompanies_withLimit_usesPageable() {
        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        int[] stats = discoveryService.resolveDiscoveredCompanies(25);

        assertThat(stats).containsExactly(0, 0, 0, 0);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(companyRepository).findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void resolveDiscoveredCompanies_highConfidence_createsEndpoint() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Acme Corp")
                .domain(null)
                .status(CompanyStatus.DISCOVERED)
                .build();

        ResolutionResultDto resolution = new ResolutionResultDto(
                List.of(new ResolutionResultDto.CandidateUrl(
                        "https://acme.com/careers", AtsType.GREENHOUSE, Confidence.HIGH, "PATTERN_MATCH")),
                "https://acme.com/careers",
                Confidence.HIGH,
                "GOOGLE_SEARCH",
                null,
                false
        );

        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any()))
                .thenReturn(List.of(company));
        when(endpointResolver.resolve("Acme Corp", null)).thenReturn(resolution);
        when(atsDetector.detectFromUrl("https://acme.com/careers"))
                .thenReturn(Optional.of(new AtsDetector.DetectionResult(AtsType.GREENHOUSE, Confidence.HIGH, "acme")));

        int[] stats = discoveryService.resolveDiscoveredCompanies(null);

        assertThat(stats[0]).isEqualTo(1); // total
        assertThat(stats[1]).isEqualTo(1); // resolved
        assertThat(stats[2]).isEqualTo(0); // failed
        assertThat(stats[3]).isEqualTo(0); // skipped
        verify(careerEndpointRepository).save(any());
        verify(companyRepository).save(company);
    }

    @Test
    void resolveDiscoveredCompanies_ambiguousConfidence_skips() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Vague Inc")
                .domain(null)
                .status(CompanyStatus.DISCOVERED)
                .build();

        ResolutionResultDto resolution = new ResolutionResultDto(
                List.of(),
                "https://vague.com/jobs",
                Confidence.AMBIGUOUS,
                "PATTERN_MATCH",
                "Multiple candidates",
                true
        );

        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any()))
                .thenReturn(List.of(company));
        when(endpointResolver.resolve("Vague Inc", null)).thenReturn(resolution);

        int[] stats = discoveryService.resolveDiscoveredCompanies(null);

        assertThat(stats[0]).isEqualTo(1); // total
        assertThat(stats[1]).isEqualTo(0); // resolved
        assertThat(stats[2]).isEqualTo(0); // failed
        assertThat(stats[3]).isEqualTo(1); // skipped
        verify(careerEndpointRepository, never()).save(any());
    }

    @Test
    void resolveDiscoveredCompanies_nullSelectedUrl_skips() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Ghost LLC")
                .domain(null)
                .status(CompanyStatus.DISCOVERED)
                .build();

        ResolutionResultDto resolution = new ResolutionResultDto(
                List.of(),
                null,
                Confidence.LOW,
                "NONE",
                null,
                true
        );

        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any()))
                .thenReturn(List.of(company));
        when(endpointResolver.resolve("Ghost LLC", null)).thenReturn(resolution);

        int[] stats = discoveryService.resolveDiscoveredCompanies(null);

        assertThat(stats[3]).isEqualTo(1); // skipped
        verify(careerEndpointRepository, never()).save(any());
    }

    @Test
    void resolveDiscoveredCompanies_resolverThrows_countsFailed() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Broken Co")
                .domain("broken.com")
                .status(CompanyStatus.DISCOVERED)
                .build();

        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any()))
                .thenReturn(List.of(company));
        when(endpointResolver.resolve("Broken Co", "broken.com")).thenThrow(new RuntimeException("Network error"));

        int[] stats = discoveryService.resolveDiscoveredCompanies(null);

        assertThat(stats[0]).isEqualTo(1); // total
        assertThat(stats[1]).isEqualTo(0); // resolved
        assertThat(stats[2]).isEqualTo(1); // failed
        assertThat(stats[3]).isEqualTo(0); // skipped
    }

    @Test
    void resolveDiscoveredCompanies_mixedResults_returnsCorrectStats() {
        Company resolved = Company.builder()
                .id(UUID.randomUUID()).name("Good Co").domain(null).status(CompanyStatus.DISCOVERED).build();
        Company skipped = Company.builder()
                .id(UUID.randomUUID()).name("Skip Co").domain(null).status(CompanyStatus.DISCOVERED).build();
        Company failed = Company.builder()
                .id(UUID.randomUUID()).name("Fail Co").domain(null).status(CompanyStatus.DISCOVERED).build();

        ResolutionResultDto goodResolution = new ResolutionResultDto(
                List.of(new ResolutionResultDto.CandidateUrl(
                        "https://good.co/jobs", AtsType.LEVER, Confidence.MEDIUM, "GOOGLE_SEARCH")),
                "https://good.co/jobs",
                Confidence.MEDIUM,
                "GOOGLE_SEARCH",
                null,
                false
        );
        ResolutionResultDto skipResolution = new ResolutionResultDto(
                List.of(), null, Confidence.LOW, "NONE", null, true
        );

        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any()))
                .thenReturn(List.of(resolved, skipped, failed));
        when(endpointResolver.resolve("Good Co", null)).thenReturn(goodResolution);
        when(endpointResolver.resolve("Skip Co", null)).thenReturn(skipResolution);
        when(endpointResolver.resolve("Fail Co", null)).thenThrow(new RuntimeException("timeout"));
        when(atsDetector.detectFromUrl("https://good.co/jobs"))
                .thenReturn(Optional.of(new AtsDetector.DetectionResult(AtsType.LEVER, Confidence.MEDIUM, "good-co")));

        int[] stats = discoveryService.resolveDiscoveredCompanies(null);

        assertThat(stats).containsExactly(3, 1, 1, 1);
    }

    @Test
    void resolveDiscoveredCompanies_companyWithDomain_passesDomain() {
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Domain Co")
                .domain("domain.co")
                .status(CompanyStatus.DISCOVERED)
                .build();

        ResolutionResultDto resolution = new ResolutionResultDto(
                List.of(), null, Confidence.LOW, "NONE", null, true
        );

        when(companyRepository.findByStatusAndNoEndpoints(eq(CompanyStatus.DISCOVERED), any()))
                .thenReturn(List.of(company));
        when(endpointResolver.resolve("Domain Co", "domain.co")).thenReturn(resolution);

        discoveryService.resolveDiscoveredCompanies(null);

        verify(endpointResolver).resolve("Domain Co", "domain.co");
    }
}
