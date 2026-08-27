package dev.jobhunter.ingestion;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.filter.YoeFilter;
import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
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
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
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
    @Mock private YoeFilter yoeFilter;
    @Mock private VisaSponsorshipFilter visaSponsorshipFilter;
    @Mock private CityCountryResolver cityCountryResolver;

    private AggregatorDescriptionEnricher enricher;
    private String baseUrl;
    private WireMock wireMockClient;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        baseUrl = wmInfo.getHttpBaseUrl();
        wireMockClient = wmInfo.getWireMock();

        WebClient webClient = WebClient.builder()
                .filter(rewriteExternalHostsToWireMock())
                .build();

        DescriptionFilterChain descriptionFilterChain = new DescriptionFilterChain(
                languageFilter, yoeFilter, visaSponsorshipFilter, cityCountryResolver);

        lenient().when(languageFilter.filter(any(), any())).thenReturn(FilterResult.keep());
        lenient().when(yoeFilter.extractYoe(any())).thenReturn(null);
        lenient().when(yoeFilter.filter(any())).thenReturn(FilterResult.keep());
        lenient().when(visaSponsorshipFilter.filter(any(), anyBoolean())).thenReturn(VisaFilterResult.keep(VisaSponsorship.UNKNOWN));
        lenient().when(cityCountryResolver.isVisaExempt(any())).thenReturn(false);

        enricher = new AggregatorDescriptionEnricher(
                webClient, jobPostingRepository, matchScoreRepository,
                descriptionFilterChain, 5, 0, 50);
    }

    private ExchangeFilterFunction rewriteExternalHostsToWireMock() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            URI uri = req.url();
            String host = uri.getHost();
            if (host != null && !host.startsWith("localhost") && !host.startsWith("127.")) {
                String newUrl = baseUrl + uri.getRawPath()
                        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                return Mono.just(org.springframework.web.reactive.function.client.ClientRequest
                        .from(req).url(URI.create(newUrl)).build());
            }
            return Mono.just(req);
        });
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
    void enrich_zeroCreated_stillQueriesBacklog() {
        // Aggregator passes that produce zero new jobs (e.g. all duplicates/filtered)
        // must still run enrichment: the backlog query is source-agnostic and may
        // find jobs from OTHER aggregator sources whose descriptions are still null.
        // Without this, sources like WORK_IN_FINLAND (whose crawl always returns
        // created=0) would never trigger enrichment.
        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of());

        enricher.enrich(JobSource.WORK_IN_FINLAND, 0);

        verify(jobPostingRepository).findAggregatorJobsNeedingDescription(any(), anyInt());
    }

    @Test
    void enrich_validAggregatorSource_triggersEnrichment() {
        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of());

        enricher.enrich(JobSource.ARBEITNOW, 3);

        verify(jobPostingRepository).findAggregatorJobsNeedingDescription(any(), eq(50));
    }

    @Test
    void enrich_berlinStartupJobs_triggersEnrichment() {
        when(jobPostingRepository.findAggregatorJobsNeedingDescription(any(), anyInt()))
                .thenReturn(List.of());

        enricher.enrich(JobSource.BERLIN_STARTUP_JOBS, 1);

        verify(jobPostingRepository).findAggregatorJobsNeedingDescription(any(), eq(50));
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

        // job1: fetch fails with 500 → catch block saves once (url-dead-500).
        // job2: HTML returns <500 chars of plain text → short-text branch fires,
        // bumpStuckOrDeactivate returns false (counter still below threshold),
        // non-pending fallback saves once so the stuck-counter persists.
        verify(jobPostingRepository, times(2)).save(any(JobPosting.class));
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

        // Non-pending job with existing description: short-text branch fires, the
        // stuck-counter (enrich-stuck-1) is persisted so it can advance on future attempts.
        verify(jobPostingRepository, times(1)).save(any());
        assertThat(job.getFilterReason()).isEqualTo("enrich-stuck-1");
    }

    @Test
    void enrichDescriptions_respectsBatchSize() {
        WebClient webClient = WebClient.builder()
                .filter(rewriteExternalHostsToWireMock())
                .build();
        DescriptionFilterChain descriptionFilterChain = new DescriptionFilterChain(
                languageFilter, yoeFilter, visaSponsorshipFilter, cityCountryResolver);
        enricher = new AggregatorDescriptionEnricher(
                webClient, jobPostingRepository, matchScoreRepository,
                descriptionFilterChain, 2, 0, 50);

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

        // Non-pending jobs: bumpStuckOrDeactivate bumps the stuck-counter (enrich-stuck-1)
        // and the caller saves once so the counter persists across cycles. Visa/active state
        // remain unchanged.
        verify(jobPostingRepository, times(1)).save(any());
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(job.isActive()).isTrue();
        assertThat(job.getFilterReason()).isEqualTo("enrich-stuck-1");
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
        String html = "<html><body><p>A detailed job description for a senior Java developer with Spring Boot experience and Kafka and microservices.</p></body></html>";
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
        // yoeFilter throws during refilter → outer catch fires
        when(yoeFilter.extractYoe(any())).thenThrow(new RuntimeException("AI service unavailable"));
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

    // --- Host-based extraction tests ---

    @Test
    void fetchDescription_theHubHost_callsApiAndExtractsDocDescription() throws Exception {
        String apiJson = "{\"doc\":{\"description\":\"<h4>About</h4><p>REAL THEHUB DESCRIPTION TEXT here long enough to exceed five hundred characters and contain useful job detail content for scoring purposes. We are looking for a Java developer to join our backend team and work on cloud native microservices with Spring Boot and Kafka.</p>\"}}";
        stubFor(get("/jobs/abc123").willReturn(okJson(apiJson).withHeader("Content-Type", "application/json")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("thehub-abc123")
                .title("Java Dev")
                .applyUrl("https://thehub.fi/jobs/abc123")
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
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("REAL THEHUB DESCRIPTION TEXT");
        assertThat(savedDesc).doesNotContain("<h4>");
        assertThat(savedDesc).doesNotContain("<p>");

        wireMockClient.verify(getRequestedFor(urlEqualTo("/jobs/abc123")));
    }

    @Test
    void fetchDescription_joblyHost_parsesLdJsonJobPosting() throws Exception {
        String innerJson = "{\"@type\":\"JobPosting\",\"description\":\"<p>REAL JOBLY DESCRIPTION TEXT here long enough to exceed five hundred characters and contain useful job detail content for scoring purposes. We need a senior Java engineer with Spring Boot experience for our growing SaaS platform based in Helsinki Finland.</p>\"}";
        String html = "<html><head>" +
                "<script type=\"application/ld+json\">" + innerJson + "</script>" +
                "</head><body></body></html>";
        stubFor(get("/en/job/example-1").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("jobly-example-1")
                .title("Senior Java Engineer")
                .applyUrl("https://www.jobly.fi/en/job/example-1")
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
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("REAL JOBLY DESCRIPTION TEXT");
        assertThat(savedDesc).doesNotContain("<p>");

        wireMockClient.verify(getRequestedFor(urlEqualTo("/en/job/example-1")));
    }

    @Test
    void fetchDescription_tyomarkkinatoriHost_callsPublicApi() throws Exception {
        String apiJson = "{\"position\":{\"jobDescription\":{\"en\":\"REAL TYOMARKKINATORI DESCRIPTION prose here long enough to exceed five hundred characters and contain useful job detail content for scoring purposes. Helsinki based software team hiring a backend developer with strong Java and Spring Boot skills and experience building event driven systems.\"},\"marketingDescription\":{\"en\":\"short\"}}}";
        UUID postUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        String applyPath = "/en/personal-customers/vacancies/" + postUuid;
        String apiPath = "/api/jobposting-new/v1/public/jobpostings/" + postUuid;
        stubFor(get(apiPath)
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Referer", equalTo("https://tyomarkkinatori.fi" + applyPath))
                .withHeader("Origin", equalTo("https://tyomarkkinatori.fi"))
                .willReturn(okJson(apiJson).withHeader("Content-Type", "application/json")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("tyomarkkinatori-" + postUuid)
                .title("Backend Developer")
                .applyUrl("https://tyomarkkinatori.fi" + applyPath)
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
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("REAL TYOMARKKINATORI DESCRIPTION");

        wireMockClient.verify(getRequestedFor(urlEqualTo(apiPath))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void fetchDescription_tyomarkkinatoriHost_nonUuidPath_returnsNull() {
        // Defensive stub: the code must short-circuit on the UUID check BEFORE hitting
        // the network. If a future regression skips the check, this stub catches the
        // call and returns an empty body (which the catch-all would also treat as null).
        stubFor(get("/api/jobposting-new/v1/public/jobpostings/not-a-uuid")
                .willReturn(okJson("{}").withHeader("Content-Type", "application/json")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("tyomarkkinatori-not-uuid")
                .title("Backend Developer")
                .applyUrl("https://tyomarkkinatori.fi/en/personal-customers/vacancies/not-a-uuid")
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
        JobPosting saved = captor.getValue();
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getFilterReason()).isEqualTo("enrich-stuck-1");
        assertThat(saved.isActive()).isTrue();

        // The non-UUID short-circuit must skip the API call entirely.
        wireMockClient.verify(0, getRequestedFor(urlEqualTo("/api/jobposting-new/v1/public/jobpostings/not-a-uuid")));
    }

    @Test
    void fetchDescription_tyomarkkinatoriHost_404Response_returnsNull() {
        UUID postUuid = UUID.randomUUID();
        stubFor(get("/api/jobposting-new/v1/public/jobpostings/" + postUuid)
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Referer", equalTo("https://tyomarkkinatori.fi/en/personal-customers/vacancies/" + postUuid))
                .withHeader("Origin", equalTo("https://tyomarkkinatori.fi"))
                .willReturn(notFound()));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("tyomarkkinatori-removed-" + postUuid)
                .title("Removed Posting")
                .applyUrl("https://tyomarkkinatori.fi/en/personal-customers/vacancies/" + postUuid)
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
        JobPosting saved = captor.getValue();
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getFilterReason()).isEqualTo("enrich-stuck-1");
        assertThat(saved.isActive()).isTrue();

        wireMockClient.verify(getRequestedFor(urlEqualTo("/api/jobposting-new/v1/public/jobpostings/" + postUuid))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void fetchDescription_unknownHost_fallsBackToDefaultPageExtraction() {
        String bodyText = "A great position for a Java engineer with Spring Boot experience and exposure to Kafka and PostgreSQL. The role involves designing and building microservices in a cloud environment and collaborating with product teams to deliver high quality software at pace. The successful candidate will have a strong foundation in object oriented design, solid communication skills, and a passion for clean code and testable architecture. Helsinki based hybrid working with flexible hours and excellent compensation package offered to the right candidate.";
        String html = "<html><body><nav>Skip</nav><main><p>" + bodyText + "</p></main><footer>Legal</footer></body></html>";
        stubFor(get("/jobs/1").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("unknown-host-1")
                .title("Java Engineer")
                .applyUrl("https://example.com/jobs/1")
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
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("great position for a Java engineer");
        assertThat(savedDesc).doesNotContain("Skip");
        assertThat(savedDesc).doesNotContain("Legal");
    }

    @Test
    void fetchDescription_unknownHost_jsonLdFirst_extractsDescription() {
        // Tier 1 must win when the HTML carries a JSON-LD JobPosting with a real description,
        // even if Tier 2 (plain body) would only see nav/shell text.
        String longDesc = "REAL JSONLD DESCRIPTION here long enough to exceed five hundred characters " +
                "and contain useful job detail content for scoring purposes. We are hiring a senior " +
                "Java engineer with Spring Boot and Kafka experience for our Helsinki based team " +
                "building cloud native microservices in a fast paced engineering culture.";
        String innerJson = "{\"@type\":\"JobPosting\",\"description\":\"<p>" + longDesc + "</p>\"}";
        String html = "<html><body>" +
                "<script type=\"application/ld+json\">" + innerJson + "</script>" +
                "<main>nav junk here</main>" +
                "</body></html>";
        stubFor(get("/jobs/1").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("unknown-host-jsonld")
                .title("Senior Java Engineer")
                .applyUrl("https://example.com/jobs/1")
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
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("REAL JSONLD DESCRIPTION");
        assertThat(savedDesc).doesNotContain("<p>");
        // Tier 1 should win over Tier 2 — saved description must not contain the body nav text.
        assertThat(savedDesc).doesNotContain("nav junk here");
    }

    @Test
    void fetchDescription_unknownHost_shortBodyFallsThroughToReaderProxy() {
        // Tier 2 yields a short body (< 50 chars). Tier 3 (r.jina.ai reader proxy) must
        // be called and its markdown result must be returned when long enough.
        stubFor(get("/jobs/2").willReturn(ok("<p>Tiny.</p>").withHeader("Content-Type", "text/html")));

        String readerText = "REAL READER PROXY DESCRIPTION long enough to exceed five hundred characters " +
                "and contain useful job detail content for scoring purposes. We are hiring a backend " +
                "engineer with Java and Spring Boot for our Helsinki based engineering team working on " +
                "cloud native microservices with Kafka and PostgreSQL in a modern DevOps culture.";
        stubFor(get(urlPathMatching("/https://example\\.com/jobs/2"))
                .willReturn(ok(readerText).withHeader("Content-Type", "text/plain")));

        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder()
                .id(jobId)
                .source(JobSource.WORK_IN_FINLAND)
                .externalId("unknown-host-reader")
                .title("Backend Engineer")
                .applyUrl("https://example.com/jobs/2")
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
        String savedDesc = captor.getValue().getDescription();
        assertThat(savedDesc).contains("REAL READER PROXY DESCRIPTION");
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
