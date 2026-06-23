package dev.jobhunter.ingestion;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.visa.VisaDetectionChain;
import dev.jobhunter.filter.visa.VisaDetectionResult;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@WireMockTest
class AggregatorDescriptionEnricherTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private MatchScoreRepository matchScoreRepository;
    @Mock private LanguageFilter languageFilter;
    @Mock private VisaDetectionChain visaDetectionChain;

    private AggregatorDescriptionEnricher enricher;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        baseUrl = wmInfo.getHttpBaseUrl();

        WebClient webClient = WebClient.builder().build();

        lenient().when(languageFilter.filter(any(), any())).thenReturn(FilterResult.keep());

        enricher = new AggregatorDescriptionEnricher(
                webClient, jobPostingRepository, matchScoreRepository, languageFilter,
                visaDetectionChain, 5, 0, 500);
    }

    // --- enrich() gateway tests ---

    @Test
    void enrich_linkedInSource_doesNothing() {
        enricher.enrich(JobSource.LINKEDIN, 5);

        verify(jobPostingRepository, never()).findAggregatorJobsNeedingDescription(any(), anyInt());
    }

    @Test
    void enrich_nonAggregatorSource_doesNothing() {
        enricher.enrich(JobSource.GREENHOUSE, 3);

        verify(jobPostingRepository, never()).findAggregatorJobsNeedingDescription(any(), anyInt());
    }

    @Test
    void enrich_zeroCreated_doesNothing() {
        enricher.enrich(JobSource.ARBEITNOW, 0);

        verify(jobPostingRepository, never()).findAggregatorJobsNeedingDescription(any(), anyInt());
    }

    @Test
    void enrich_validAggregatorSource_triggersEnrichment() {
        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of());

        enricher.enrich(JobSource.ARBEITNOW, 3);

        verify(jobPostingRepository).findAggregatorJobsNeedingDescription(any(), eq(500));
    }

    @Test
    void enrich_berlinStartupJobs_triggersEnrichment() {
        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of());

        enricher.enrich(JobSource.BERLIN_STARTUP_JOBS, 1);

        verify(jobPostingRepository).findAggregatorJobsNeedingDescription(any(), eq(500));
    }

    // --- enrichDescriptions tests ---

    @Test
    void enrichDescriptions_noJobsFound_doesNothing() {
        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of());

        enricher.enrichDescriptions();

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void enrichDescriptions_fetchesPageAndUpdatesDescription() {
        String html = """
                <html><head><script>var x=1;</script></head>
                <body>
                <nav>Menu</nav>
                <main><p>We are looking for a Backend Engineer with Java and Spring Boot experience.
                Must have 3+ years of experience building microservices.</p></main>
                <footer>Copyright 2024</footer>
                </body></html>
                """;
        stubFor(get("/jobs/123").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-123")
                .title("Backend Engineer")
                .applyUrl(baseUrl + "/jobs/123")
                .description("Short stub")
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("Backend Engineer with Java");
        assertThat(savedDesc).doesNotContain("Menu");
        assertThat(savedDesc).doesNotContain("Copyright");
        assertThat(savedDesc).doesNotContain("var x=1");
    }

    @Test
    void enrichDescriptions_deletesMatchScoreForRescore() {
        String html = "<html><body><p>A job description that is long enough to pass all checks.</p></body></html>";
        stubFor(get("/jobs/456").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.INDEED)
                .externalId("ind-456")
                .title("Java Dev")
                .applyUrl(baseUrl + "/jobs/456")
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        verify(matchScoreRepository).deleteByJobId(jobId);
    }

    @Test
    void enrichDescriptions_languageFilterRejects_setsFilterDecisionToSkip() {
        String html = "<html><body><p>Wir suchen einen erfahrenen Softwareentwickler mit fließend Deutsch.</p></body></html>";
        stubFor(get("/jobs/789").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.BERLIN_STARTUP_JOBS)
                .externalId("bsj-789")
                .title("Softwareentwickler")
                .applyUrl(baseUrl + "/jobs/789")
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        when(languageFilter.filter(eq("Softwareentwickler"), any()))
                .thenReturn(FilterResult.skip("German required"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    void enrichDescriptions_fetchFails_continuesWithNextJob() {
        stubFor(get("/jobs/fail").willReturn(serverError().withBody("Server Error")));
        String html = "<html><body><p>Great job opportunity for a developer with strong skills.</p></body></html>";
        stubFor(get("/jobs/success").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job1 = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-fail")
                .title("Dev 1")
                .applyUrl(baseUrl + "/jobs/fail")
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        JobPosting job2 = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-ok")
                .title("Dev 2")
                .applyUrl(baseUrl + "/jobs/success")
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job1, job2));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        // Only job2 should be saved (job1 fetch failed)
        verify(jobPostingRepository, times(1)).save(any(JobPosting.class));
    }

    @Test
    void enrichDescriptions_extractedTextShorterThanCurrent_skipsUpdate() {
        String html = "<html><body><p>Short</p></body></html>";
        stubFor(get("/jobs/short").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        String existingDesc = "This is an existing description that is already longer than what the page would return from parsing.";
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-short")
                .title("Dev")
                .applyUrl(baseUrl + "/jobs/short")
                .description(existingDesc)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));

        enricher.enrichDescriptions();

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void enrichDescriptions_respectsBatchSize() {
        WebClient webClient = WebClient.builder().build();
        enricher = new AggregatorDescriptionEnricher(
                webClient, jobPostingRepository, matchScoreRepository, languageFilter,
                visaDetectionChain, 2, 0, 500);

        String html = "<html><body><p>A reasonable job description for a developer position.</p></body></html>";
        stubFor(get(urlPathMatching("/jobs/.*")).willReturn(ok(html).withHeader("Content-Type", "text/html")));

        List<JobPosting> jobs = List.of(
                buildJob("1"), buildJob("2"), buildJob("3"), buildJob("4")
        );

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(jobs);
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        // Only 2 saves despite 4 jobs (batch size = 2)
        verify(jobPostingRepository, times(2)).save(any(JobPosting.class));
    }

    @Test
    void enrichDescriptions_capsDescriptionAt10000Chars() {
        StringBuilder longBody = new StringBuilder("<html><body><p>");
        for (int i = 0; i < 1200; i++) {
            longBody.append("This is some text. ");
        }
        longBody.append("</p></body></html>");
        stubFor(get("/jobs/long").willReturn(ok(longBody.toString()).withHeader("Content-Type", "text/html")));

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-long")
                .title("Dev")
                .applyUrl(baseUrl + "/jobs/long")
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription().length()).isLessThanOrEqualTo(10_000);
    }

    // --- deactivatePendingVisa tests ---

    @Test
    void deactivatePendingVisa_nonPendingJob_doesNothing() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-confirmed")
                .visaSponsorship(VisaSponsorship.CONFIRMED)
                .isActive(true)
                .build();

        enricher.deactivatePendingVisa(job, "visa: pending - no description available (empty response)");

        verify(jobPostingRepository, never()).save(any());
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(job.isActive()).isTrue();
    }

    @Test
    void deactivatePendingVisa_nullSponsorship_doesNothing() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-null")
                .visaSponsorship(null)
                .isActive(true)
                .build();

        enricher.deactivatePendingVisa(job, "reason");

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void deactivatePendingVisa_pendingJob_setsUnknownAndDeactivates() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-pending")
                .visaSponsorship(VisaSponsorship.PENDING)
                .isActive(true)
                .build();

        String reason = "visa: pending - no description available (empty response)";
        enricher.deactivatePendingVisa(job, reason);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(job.isActive()).isFalse();
        assertThat(job.getFilterReason()).isEqualTo(reason);
        verify(jobPostingRepository).save(job);
    }

    @Test
    void enrichDescriptions_emptyHtml_pendingJob_deactivates() {
        stubFor(get("/jobs/empty-pending").willReturn(ok("").withHeader("Content-Type", "text/html")));

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-empty-pending")
                .title("Java Dev")
                .applyUrl(baseUrl + "/jobs/empty-pending")
                .description(null)
                .visaSponsorship(VisaSponsorship.PENDING)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getFilterReason())
                .isEqualTo("visa: pending - no description available (empty response)");
    }

    @Test
    void enrichDescriptions_emptyHtml_nonPendingJob_doesNotDeactivate() {
        stubFor(get("/jobs/empty-confirmed").willReturn(ok("").withHeader("Content-Type", "text/html")));

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-empty-confirmed")
                .title("Java Dev")
                .applyUrl(baseUrl + "/jobs/empty-confirmed")
                .description(null)
                .visaSponsorship(VisaSponsorship.CONFIRMED)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));

        enricher.enrichDescriptions();

        verify(jobPostingRepository, never()).save(any());
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(job.isActive()).isTrue();
    }

    @Test
    void enrichDescriptions_noTextImprovement_pendingJob_deactivates() {
        stubFor(get("/jobs/short-pending").willReturn(
                ok("<html><body><p>Short</p></body></html>").withHeader("Content-Type", "text/html")));

        String existingDesc = "Existing description that is definitely longer than the short extracted text.";
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-short-pending")
                .title("Java Dev")
                .applyUrl(baseUrl + "/jobs/short-pending")
                .description(existingDesc)
                .visaSponsorship(VisaSponsorship.PENDING)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getFilterReason())
                .isEqualTo("visa: pending - no description available (no better text)");
    }

    @Test
    void enrichDescriptions_exceptionDuringEnrichment_pendingJob_deactivates() {
        String html = "<html><body><p>A detailed job description for a senior Java developer with Spring Boot experience.</p></body></html>";
        stubFor(get("/jobs/exception-pending").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-exception-pending")
                .title("Java Dev")
                .applyUrl(baseUrl + "/jobs/exception-pending")
                .description(null)
                .visaSponsorship(VisaSponsorship.PENDING)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();

        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of(job));
        // visaDetectionChain throws during updateJobDescription → outer catch fires
        when(visaDetectionChain.evaluate(any())).thenThrow(new RuntimeException("AI service unavailable"));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        enricher.enrichDescriptions();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getFilterReason()).isEqualTo("visa: pending - enrichment failed");
    }

    // --- extractText tests ---

    @Test
    void extractText_removesScriptsAndStyles() {
        String html = "<html><head><style>body{color:red}</style></head>" +
                "<body><script>alert('xss')</script><p>Clean content here</p></body></html>";

        String result = enricher.extractText(html);

        assertThat(result).isEqualTo("Clean content here");
    }

    @Test
    void extractText_removesNavFooterHeader() {
        String html = "<html><body><header>Logo Nav</header><main>Job content</main><footer>Legal</footer></body></html>";

        String result = enricher.extractText(html);

        assertThat(result).isEqualTo("Job content");
    }

    @Test
    void extractText_emptyBody_returnsNull() {
        String html = "<html><body></body></html>";

        String result = enricher.extractText(html);

        assertThat(result).isNull();
    }

    @Test
    void extractText_onlyScript_returnsNull() {
        String html = "<html><body><script>var x=1;</script></body></html>";

        String result = enricher.extractText(html);

        assertThat(result).isNull();
    }

    // --- Helper ---

    private JobPosting buildJob(String suffix) {
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.ARBEITNOW)
                .externalId("arb-" + suffix)
                .title("Dev " + suffix)
                .applyUrl(baseUrl + "/jobs/" + suffix)
                .description(null)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();
    }
}
