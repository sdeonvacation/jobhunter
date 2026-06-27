package dev.jobhunter.service;

import dev.jobhunter.dto.LivenessResultDto;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.LivenessStatus;
import dev.jobhunter.repository.JobPostingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LivenessCheckServiceTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private EntityManager entityManager;
    @Mock private WebClient webClient;
    @Mock private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private Query nativeQuery;

    private LivenessCheckService service;

    @BeforeEach
    void setUp() {
        service = new LivenessCheckService(jobPostingRepository, entityManager, webClient);
    }

    @Test
    void checkLiveness_jobNotFound_returnsUncertain() {
        UUID jobId = UUID.randomUUID();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo(LivenessStatus.UNCERTAIN.name());
        assertThat(result.reason()).isEqualTo("Job not found");
    }

    @Test
    void checkLiveness_noApplyUrl_returnsUncertain() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(null).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo(LivenessStatus.UNCERTAIN.name());
        assertThat(result.reason()).isEqualTo("No apply URL available");
    }

    @Test
    void checkLiveness_blankApplyUrl_returnsUncertain() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).applyUrl("   ").build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.UNCERTAIN.name());
        assertThat(result.reason()).isEqualTo("No apply URL available");
    }

    @Test
    void checkLiveness_head404_returnsExpired() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/123";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 404);

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.EXPIRED.name());
        assertThat(result.reason()).isEqualTo("HTTP 404");
        assertThat(result.url()).isEqualTo(url);
    }

    @Test
    void checkLiveness_head410_returnsExpired() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/456";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 410);

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.EXPIRED.name());
        assertThat(result.reason()).isEqualTo("HTTP 410");
    }

    @Test
    void checkLiveness_bodyContainsExpiredIndicator_returnsExpired() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/789";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 200);
        mockGetRequest(url, "<html><body>Sorry, this position has been filled.</body></html>");

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.EXPIRED.name());
        assertThat(result.reason()).contains("position has been filled");
    }

    @Test
    void checkLiveness_bodyContainsNoLongerAccepting_returnsExpired() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/101";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 200);
        mockGetRequest(url, "<html><body>We are No Longer Accepting applications for this role.</body></html>");

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.EXPIRED.name());
        assertThat(result.reason()).contains("no longer accepting");
    }

    @Test
    void checkLiveness_bodyContainsApplyButton_returnsActive() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/200";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 200);
        mockGetRequest(url, "<html><body><button>Apply Now</button></body></html>");

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.ACTIVE.name());
        assertThat(result.reason()).isEqualTo("Page contains apply button");
    }

    @Test
    void checkLiveness_noClearSignals_returnsUncertain() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/300";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 200);
        mockGetRequest(url, "<html><body><h1>Software Engineer</h1><p>Great opportunity</p></body></html>");

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.UNCERTAIN.name());
        assertThat(result.reason()).contains("No clear expired or active signals");
    }

    @Test
    void checkLiveness_get404_returnsExpired() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/400";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        // HEAD returns 200 but GET returns 404
        mockHeadRequest(url, 200);
        mockGetRequest(url, "__STATUS_404");

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.EXPIRED.name());
        assertThat(result.reason()).contains("404");
    }

    @Test
    void checkLivenessByUrl_emptyUrl_returnsUncertain() {
        LivenessResultDto result = service.checkLiveness("");

        assertThat(result.jobId()).isNull();
        assertThat(result.status()).isEqualTo(LivenessStatus.UNCERTAIN.name());
        assertThat(result.reason()).isEqualTo("URL is empty");
    }

    @Test
    void checkLivenessByUrl_nullUrl_returnsUncertain() {
        LivenessResultDto result = service.checkLiveness((String) null);

        assertThat(result.jobId()).isNull();
        assertThat(result.status()).isEqualTo(LivenessStatus.UNCERTAIN.name());
        assertThat(result.reason()).isEqualTo("URL is empty");
    }

    @Test
    void checkLivenessByUrl_withMatchingJob_persistsStatus() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/500";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findFirstByApplyUrl(url)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 200);
        mockGetRequest(url, "<html><body><button>Apply Now</button></body></html>");

        LivenessResultDto result = service.checkLiveness(url);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo(LivenessStatus.ACTIVE.name());
        verify(entityManager).createNativeQuery(contains("UPDATE job_posting"));
    }

    @Test
    void checkLivenessByUrl_noMatchingJob_doesNotPersist() {
        String url = "https://example.com/jobs/orphan";
        when(jobPostingRepository.findFirstByApplyUrl(url)).thenReturn(Optional.empty());
        mockHeadRequest(url, 200);
        mockGetRequest(url, "<html><body><button>Apply Now</button></body></html>");

        LivenessResultDto result = service.checkLiveness(url);

        assertThat(result.jobId()).isNull();
        assertThat(result.status()).isEqualTo(LivenessStatus.ACTIVE.name());
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void getStatus_jobNotFound_returnsNullStatus() {
        UUID jobId = UUID.randomUUID();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        LivenessResultDto result = service.getStatus(jobId);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.status()).isNull();
        assertThat(result.reason()).isEqualTo("Job not found");
    }

    @Test
    void getStatus_jobExists_returnsSavedStatus() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/600";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        LocalDateTime checkedAt = LocalDateTime.of(2025, 6, 15, 10, 30);
        Object[] row = new Object[]{"ACTIVE", Timestamp.valueOf(checkedAt)};
        Query selectQuery = mock(Query.class);
        when(entityManager.createNativeQuery(contains("SELECT liveness_status")))
                .thenReturn(selectQuery);
        when(selectQuery.setParameter(eq("id"), eq(jobId))).thenReturn(selectQuery);
        when(selectQuery.getSingleResult()).thenReturn(row);

        LivenessResultDto result = service.getStatus(jobId);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.checkedAt()).isEqualTo(checkedAt);
        assertThat(result.url()).isEqualTo(url);
    }

    @Test
    void getStatus_neverChecked_returnsNullTimestamp() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/700";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        Object[] row = new Object[]{null, null};
        Query selectQuery = mock(Query.class);
        when(entityManager.createNativeQuery(contains("SELECT liveness_status")))
                .thenReturn(selectQuery);
        when(selectQuery.setParameter(eq("id"), eq(jobId))).thenReturn(selectQuery);
        when(selectQuery.getSingleResult()).thenReturn(row);

        LivenessResultDto result = service.getStatus(jobId);

        assertThat(result.status()).isNull();
        assertThat(result.checkedAt()).isNull();
    }

    @Test
    void checkLiveness_headRequestTimeout_fallsToGet() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/timeout";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        // HEAD returns error (-1 from onErrorReturn)
        mockHeadRequest(url, -1);
        mockGetRequest(url, "<html><body><button>Apply for this job</button></body></html>");

        LivenessResultDto result = service.checkLiveness(jobId);

        assertThat(result.status()).isEqualTo(LivenessStatus.ACTIVE.name());
    }

    @Test
    void checkLiveness_updatesDbColumns() {
        UUID jobId = UUID.randomUUID();
        String url = "https://example.com/jobs/persist";
        JobPosting job = JobPosting.builder().id(jobId).applyUrl(url).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockUpdateQuery();
        mockHeadRequest(url, 404);

        service.checkLiveness(jobId);

        verify(nativeQuery).setParameter(eq("status"), eq("EXPIRED"));
        verify(nativeQuery).setParameter(eq("id"), eq(jobId));
        verify(nativeQuery).executeUpdate();
    }

    // --- Helper methods ---

    @SuppressWarnings("unchecked")
    private void mockHeadRequest(String url, int statusCode) {
        WebClient.RequestHeadersUriSpec headUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headSpec = mock(WebClient.RequestHeadersSpec.class);

        when(webClient.head()).thenReturn(headUriSpec);
        when(headUriSpec.uri(eq(url))).thenReturn(headSpec);
        when(headSpec.exchangeToMono(any(Function.class))).thenReturn(
                Mono.just(statusCode).timeout(java.time.Duration.ofSeconds(10))
        );
    }

    @SuppressWarnings("unchecked")
    private void mockGetRequest(String url, String body) {
        WebClient.RequestHeadersUriSpec getUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec getSpec = mock(WebClient.RequestHeadersSpec.class);

        when(webClient.get()).thenReturn(getUriSpec);
        when(getUriSpec.uri(eq(url))).thenReturn(getSpec);
        when(getSpec.exchangeToMono(any(Function.class))).thenReturn(
                Mono.just(body).timeout(java.time.Duration.ofSeconds(10))
        );
    }

    private void mockUpdateQuery() {
        when(entityManager.createNativeQuery(contains("UPDATE job_posting")))
                .thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);
    }
}
