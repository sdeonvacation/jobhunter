package dev.jobhunter.ingestion;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@WireMockTest
class BsjApplyUrlResolverTest {

    @Mock private JobPostingRepository jobPostingRepository;

    private BsjApplyUrlResolver resolver;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        baseUrl = wmInfo.getHttpBaseUrl();
        resolver = new BsjApplyUrlResolver(WebClient.builder().build(), jobPostingRepository);
    }

    // --- enrich() gateway ---

    @Test
    void enrich_nonBsjSource_skips() {
        resolver.enrich(JobSource.ARBEITNOW, 5);

        verify(jobPostingRepository, never()).findJobsWithUnresolvedBsjUrl(any());
    }

    @Test
    void enrich_greehouseSource_skips() {
        resolver.enrich(JobSource.GREENHOUSE, 3);

        verify(jobPostingRepository, never()).findJobsWithUnresolvedBsjUrl(any());
    }

    @Test
    void enrich_bsjSource_queriesRepository() {
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of());

        resolver.enrich(JobSource.BERLIN_STARTUP_JOBS, 1);

        verify(jobPostingRepository).findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS);
    }

    @Test
    void enrich_bsjSource_zeroCreated_stillQueries() {
        // BsjApplyUrlResolver has no "created > 0" guard — it always resolves unresolved URLs
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of());

        resolver.enrich(JobSource.BERLIN_STARTUP_JOBS, 0);

        verify(jobPostingRepository).findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS);
    }

    // --- resolveApplyUrls() ---

    @Test
    void resolveApplyUrls_noJobs_doesNothing() {
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(any())).thenReturn(List.of());

        resolver.resolveApplyUrls();

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void resolveApplyUrls_primarySelector_buttonOrange_resolvesUrl() {
        String applyHref = "https://join.com/companies/acme/jobs/12345";
        String html = """
                <html><body>
                <h1>Backend Engineer</h1>
                <a href="%s" class="button button--orange button--big">Apply now</a>
                </body></html>
                """.formatted(applyHref);
        stubFor(get("/jobs/bsj-1").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("bsj-1", baseUrl + "/jobs/bsj-1");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getApplyUrl()).isEqualTo(applyHref);
    }

    @Test
    void resolveApplyUrls_fallbackSelector_applyNow_resolvesUrl() {
        String applyHref = "https://lever.co/acme/abc123";
        String html = """
                <html><body>
                <h1>Frontend Engineer</h1>
                <a href="%s">Apply now</a>
                </body></html>
                """.formatted(applyHref);
        stubFor(get("/jobs/bsj-2").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("bsj-2", baseUrl + "/jobs/bsj-2");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getApplyUrl()).isEqualTo(applyHref);
    }

    @Test
    void resolveApplyUrls_fallbackSelector_applyForThis_resolvesUrl() {
        String applyHref = "https://greenhouse.io/companies/acme/jobs/99";
        String html = """
                <html><body>
                <h1>Data Engineer</h1>
                <a href="%s">Apply for this job</a>
                </body></html>
                """.formatted(applyHref);
        stubFor(get("/jobs/bsj-3").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("bsj-3", baseUrl + "/jobs/bsj-3");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getApplyUrl()).isEqualTo(applyHref);
    }

    @Test
    void resolveApplyUrls_applyLinkPointsToBsj_doesNotOverwrite() {
        // If the extracted href still points to berlinstartupjobs.com, skip it
        String html = """
                <html><body>
                <a href="https://berlinstartupjobs.com/apply/123" class="button--orange">Apply now</a>
                </body></html>
                """;
        stubFor(get("/jobs/bsj-4").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("bsj-4", baseUrl + "/jobs/bsj-4");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));

        resolver.resolveApplyUrls();

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void resolveApplyUrls_noApplyLinkFound_doesNotSave() {
        String html = """
                <html><body>
                <h1>Some Job</h1>
                <p>No apply button here.</p>
                </body></html>
                """;
        stubFor(get("/jobs/bsj-5").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("bsj-5", baseUrl + "/jobs/bsj-5");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));

        resolver.resolveApplyUrls();

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void resolveApplyUrls_emptyResponse_doesNotSave() {
        stubFor(get("/jobs/bsj-6").willReturn(ok("").withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("bsj-6", baseUrl + "/jobs/bsj-6");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));

        resolver.resolveApplyUrls();

        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    void resolveApplyUrls_404Response_doesNotSaveAndContinues() {
        stubFor(get("/jobs/bsj-7").willReturn(notFound()));
        String applyHref = "https://join.com/companies/next/jobs/77";
        String html = """
                <html><body>
                <a href="%s" class="button--orange">Apply now</a>
                </body></html>
                """.formatted(applyHref);
        stubFor(get("/jobs/bsj-8").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting gone = bsjJob("bsj-7", baseUrl + "/jobs/bsj-7");
        JobPosting ok   = bsjJob("bsj-8", baseUrl + "/jobs/bsj-8");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(gone, ok));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        // Only the second job should be saved
        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).isEqualTo("bsj-8");
        assertThat(captor.getValue().getApplyUrl()).isEqualTo(applyHref);
    }

    @Test
    void resolveApplyUrls_500Response_doesNotSaveAndContinues() {
        stubFor(get("/jobs/bsj-err").willReturn(serverError()));

        String applyHref = "https://ashby.io/acme/jobs/1";
        String html = """
                <html><body>
                <a href="%s" class="button--orange">Apply now</a>
                </body></html>
                """.formatted(applyHref);
        stubFor(get("/jobs/bsj-ok").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting errJob = bsjJob("bsj-err", baseUrl + "/jobs/bsj-err");
        JobPosting okJob  = bsjJob("bsj-ok",  baseUrl + "/jobs/bsj-ok");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(errJob, okJob));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        // errJob not saved; okJob saved
        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).isEqualTo("bsj-ok");
    }

    @Test
    void resolveApplyUrls_multipleJobs_resolvesAll() {
        String href1 = "https://join.com/companies/a/jobs/1";
        String href2 = "https://lever.co/b/abc";
        String html1 = "<html><body><a href=\"%s\" class=\"button--orange\">Apply now</a></body></html>".formatted(href1);
        String html2 = "<html><body><a href=\"%s\" class=\"button--orange\">Apply now</a></body></html>".formatted(href2);
        stubFor(get("/j/1").willReturn(ok(html1).withHeader("Content-Type", "text/html")));
        stubFor(get("/j/2").willReturn(ok(html2).withHeader("Content-Type", "text/html")));

        JobPosting job1 = bsjJob("ext-1", baseUrl + "/j/1");
        JobPosting job2 = bsjJob("ext-2", baseUrl + "/j/2");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job1, job2));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        verify(jobPostingRepository, times(2)).save(any(JobPosting.class));
        assertThat(job1.getApplyUrl()).isEqualTo(href1);
        assertThat(job2.getApplyUrl()).isEqualTo(href2);
    }

    // --- extractApplyUrl (via resolveApplyUrls integration) ---

    @Test
    void extractApplyUrl_primarySelectorTakesPrecedenceOverFallback() {
        // Both button--orange and "Apply now" present — should pick button--orange
        String orangeHref  = "https://join.com/primary/1";
        String fallbackHref = "https://join.com/fallback/2";
        String html = """
                <html><body>
                <a href="%s">Apply now</a>
                <a href="%s" class="button--orange">Apply here</a>
                </body></html>
                """.formatted(fallbackHref, orangeHref);
        stubFor(get("/jobs/prio").willReturn(ok(html).withHeader("Content-Type", "text/html")));

        JobPosting job = bsjJob("prio", baseUrl + "/jobs/prio");
        when(jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS))
                .thenReturn(List.of(job));
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolveApplyUrls();

        ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
        verify(jobPostingRepository).save(captor.capture());
        assertThat(captor.getValue().getApplyUrl()).isEqualTo(orangeHref);
    }

    // --- helpers ---

    private JobPosting bsjJob(String externalId, String applyUrl) {
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.BERLIN_STARTUP_JOBS)
                .externalId(externalId)
                .title("Some Job")
                .applyUrl(applyUrl)
                .languageFilter(FilterDecision.KEEP)
                .isActive(true)
                .build();
    }
}
