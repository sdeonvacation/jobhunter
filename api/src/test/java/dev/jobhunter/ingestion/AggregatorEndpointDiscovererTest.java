package dev.jobhunter.ingestion;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.resolution.AtsDetector;
import dev.jobhunter.resolution.AtsDetector.DetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AggregatorEndpointDiscovererTest {

    @Mock private AtsDetector atsDetector;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private CareerEndpointRepository careerEndpointRepository;

    private AggregatorEndpointDiscoverer discoverer;

    @BeforeEach
    void setUp() {
        discoverer = new AggregatorEndpointDiscoverer(true, atsDetector, jobPostingRepository, careerEndpointRepository);
    }

    // --- Guard conditions ---

    @Test
    void enrich_disabledConfig_skips() {
        var disabled = new AggregatorEndpointDiscoverer(false, atsDetector, jobPostingRepository, careerEndpointRepository);

        disabled.enrich(JobSource.ARBEITNOW, 5);

        verifyNoInteractions(jobPostingRepository, careerEndpointRepository, atsDetector);
    }

    @Test
    void enrich_zeroCreated_skips() {
        discoverer.enrich(JobSource.ARBEITNOW, 0);

        verifyNoInteractions(jobPostingRepository, careerEndpointRepository, atsDetector);
    }

    @Test
    void enrich_nonAggregatorSource_skips() {
        discoverer.enrich(JobSource.GREENHOUSE, 3);

        verifyNoInteractions(jobPostingRepository, careerEndpointRepository, atsDetector);
    }

    // --- Discovery logic ---

    @Test
    void enrich_discoversNewGreenhouseEndpoint() {
        UUID companyId = UUID.randomUUID();
        String applyUrl = "https://boards.greenhouse.io/acme/jobs/12345";
        var projection = mockProjection(applyUrl, companyId);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.GREENHOUSE, Confidence.HIGH, "acme")));
        when(careerEndpointRepository.findEndpointKeysByCompanyIds(anyList()))
                .thenReturn(List.of());
        when(careerEndpointRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        discoverer.enrich(JobSource.ARBEITNOW, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CareerEndpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(careerEndpointRepository).saveAll(captor.capture());

        List<CareerEndpoint> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        CareerEndpoint ep = saved.get(0);
        assertThat(ep.getUrl()).isEqualTo("https://boards.greenhouse.io/acme");
        assertThat(ep.getAtsType()).isEqualTo(AtsType.GREENHOUSE);
        assertThat(ep.getAtsSlug()).isEqualTo("acme");
        assertThat(ep.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(ep.isActive()).isTrue();
        assertThat(ep.isVerified()).isFalse();
        assertThat(ep.getSource()).isEqualTo("aggregator-discovery:arbeitnow");
        assertThat(ep.getCompany().getId()).isEqualTo(companyId);
    }

    @Test
    void enrich_discoversLeverEuEndpoint() {
        UUID companyId = UUID.randomUUID();
        String applyUrl = "https://jobs.eu.lever.co/coolcompany/abc123";
        var projection = mockProjection(applyUrl, companyId);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("BERLIN_STARTUP_JOBS"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.LEVER_EU, Confidence.HIGH, "coolcompany")));
        when(careerEndpointRepository.findEndpointKeysByCompanyIds(anyList()))
                .thenReturn(List.of());
        when(careerEndpointRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        discoverer.enrich(JobSource.BERLIN_STARTUP_JOBS, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CareerEndpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(careerEndpointRepository).saveAll(captor.capture());

        CareerEndpoint ep = captor.getValue().get(0);
        assertThat(ep.getUrl()).isEqualTo("https://jobs.eu.lever.co/coolcompany");
        assertThat(ep.getAtsType()).isEqualTo(AtsType.LEVER_EU);
    }

    @Test
    void enrich_skipsExistingEndpoint() {
        UUID companyId = UUID.randomUUID();
        String applyUrl = "https://boards.greenhouse.io/existing/jobs/999";
        var projection = mockProjection(applyUrl, companyId);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.GREENHOUSE, Confidence.HIGH, "existing")));

        // Existing endpoint already in DB
        var existingKey = mockEndpointKey(companyId, "GREENHOUSE", "existing");
        when(careerEndpointRepository.findEndpointKeysByCompanyIds(anyList()))
                .thenReturn(List.of(existingKey));

        discoverer.enrich(JobSource.ARBEITNOW, 1);

        verify(careerEndpointRepository, never()).saveAll(anyList());
    }

    @Test
    void enrich_skipsUnknownAtsType() {
        String applyUrl = "https://some-random-ats.com/jobs/123";
        var projection = mock(JobPostingRepository.ApplyUrlProjection.class);
        when(projection.getApplyUrl()).thenReturn(applyUrl);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.empty());

        discoverer.enrich(JobSource.ARBEITNOW, 1);

        verify(careerEndpointRepository, never()).findEndpointKeysByCompanyIds(anyList());
        verify(careerEndpointRepository, never()).saveAll(anyList());
    }

    @Test
    void enrich_skipsAggregatorAtsTypes() {
        String applyUrl = "https://www.stepstone.de/job/backend-dev/12345";
        var projection = mock(JobPostingRepository.ApplyUrlProjection.class);
        when(projection.getApplyUrl()).thenReturn(applyUrl);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.STEPSTONE, Confidence.HIGH, null)));

        discoverer.enrich(JobSource.ARBEITNOW, 1);

        verify(careerEndpointRepository, never()).findEndpointKeysByCompanyIds(anyList());
        verify(careerEndpointRepository, never()).saveAll(anyList());
    }

    @Test
    void enrich_skipsWorkdayNoUrl() {
        String applyUrl = "https://company.wd5.myworkdayjobs.com/en-US/External/job/Berlin/Backend_12345";
        var projection = mock(JobPostingRepository.ApplyUrlProjection.class);
        when(projection.getApplyUrl()).thenReturn(applyUrl);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        // Workday detects without a slug (pattern has no capture group for slug)
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.WORKDAY, Confidence.HIGH, null)));

        discoverer.enrich(JobSource.ARBEITNOW, 1);

        verify(careerEndpointRepository, never()).findEndpointKeysByCompanyIds(anyList());
        verify(careerEndpointRepository, never()).saveAll(anyList());
    }

    @Test
    void enrich_batchDedupWithinRun() {
        UUID companyId = UUID.randomUUID();
        // Two jobs from same company pointing to same ATS board
        String applyUrl1 = "https://boards.greenhouse.io/acme/jobs/111";
        String applyUrl2 = "https://boards.greenhouse.io/acme/jobs/222";
        var p1 = mockProjection(applyUrl1, companyId);
        var p2 = mockProjection(applyUrl2, companyId);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(p1, p2));
        when(atsDetector.detectFromUrl(applyUrl1))
                .thenReturn(Optional.of(new DetectionResult(AtsType.GREENHOUSE, Confidence.HIGH, "acme")));
        when(atsDetector.detectFromUrl(applyUrl2))
                .thenReturn(Optional.of(new DetectionResult(AtsType.GREENHOUSE, Confidence.HIGH, "acme")));
        when(careerEndpointRepository.findEndpointKeysByCompanyIds(anyList()))
                .thenReturn(List.of());
        when(careerEndpointRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        discoverer.enrich(JobSource.ARBEITNOW, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CareerEndpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(careerEndpointRepository).saveAll(captor.capture());
        // Only one endpoint despite two jobs
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void enrich_confidenceGatesActivation_mediumIsInactive() {
        UUID companyId = UUID.randomUUID();
        String applyUrl = "https://jobs.ashbyhq.com/startup/jobs/abc";
        var projection = mockProjection(applyUrl, companyId);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.ASHBY, Confidence.MEDIUM, "startup")));
        when(careerEndpointRepository.findEndpointKeysByCompanyIds(anyList()))
                .thenReturn(List.of());
        when(careerEndpointRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        discoverer.enrich(JobSource.ARBEITNOW, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CareerEndpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(careerEndpointRepository).saveAll(captor.capture());

        CareerEndpoint ep = captor.getValue().get(0);
        assertThat(ep.isActive()).isFalse();
        assertThat(ep.getConfidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void enrich_pinpointUrlFormat() {
        UUID companyId = UUID.randomUUID();
        String applyUrl = "https://coolstartup.pinpointhq.com/jobs/12345";
        var projection = mockProjection(applyUrl, companyId);

        when(jobPostingRepository.findApplyUrlsBySourceAndDate(eq("ARBEITNOW"), any(LocalDate.class)))
                .thenReturn(List.of(projection));
        when(atsDetector.detectFromUrl(applyUrl))
                .thenReturn(Optional.of(new DetectionResult(AtsType.PINPOINT, Confidence.HIGH, "coolstartup")));
        when(careerEndpointRepository.findEndpointKeysByCompanyIds(anyList()))
                .thenReturn(List.of());
        when(careerEndpointRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        discoverer.enrich(JobSource.ARBEITNOW, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CareerEndpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(careerEndpointRepository).saveAll(captor.capture());

        assertThat(captor.getValue().get(0).getUrl()).isEqualTo("https://coolstartup.pinpointhq.com");
    }

    // --- buildBoardUrl static tests ---

    @Test
    void buildBoardUrl_greenhouse() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.GREENHOUSE, "acme"))
                .isEqualTo("https://boards.greenhouse.io/acme");
    }

    @Test
    void buildBoardUrl_lever() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.LEVER, "myco"))
                .isEqualTo("https://jobs.lever.co/myco");
    }

    @Test
    void buildBoardUrl_leverEu() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.LEVER_EU, "eucompany"))
                .isEqualTo("https://jobs.eu.lever.co/eucompany");
    }

    @Test
    void buildBoardUrl_ashby() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.ASHBY, "startup"))
                .isEqualTo("https://jobs.ashbyhq.com/startup");
    }

    @Test
    void buildBoardUrl_pinpoint() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.PINPOINT, "coolco"))
                .isEqualTo("https://coolco.pinpointhq.com");
    }

    @Test
    void buildBoardUrl_workday_returnsNull() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.WORKDAY, "wd")).isNull();
    }

    @Test
    void buildBoardUrl_smartrecruiters_returnsNull() {
        assertThat(AggregatorEndpointDiscoverer.buildBoardUrl(AtsType.SMARTRECRUITERS, "sr")).isNull();
    }

    // --- Helpers ---

    private JobPostingRepository.ApplyUrlProjection mockProjection(String applyUrl, UUID companyId) {
        var projection = mock(JobPostingRepository.ApplyUrlProjection.class);
        when(projection.getApplyUrl()).thenReturn(applyUrl);
        when(projection.getCompanyId()).thenReturn(companyId);
        return projection;
    }

    private CareerEndpointRepository.EndpointKeyProjection mockEndpointKey(UUID companyId, String atsType, String atsSlug) {
        var projection = mock(CareerEndpointRepository.EndpointKeyProjection.class);
        when(projection.getCompanyId()).thenReturn(companyId);
        when(projection.getAtsType()).thenReturn(atsType);
        when(projection.getAtsSlug()).thenReturn(atsSlug);
        return projection;
    }
}
