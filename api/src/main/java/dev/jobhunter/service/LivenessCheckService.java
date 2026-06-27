package dev.jobhunter.service;

import dev.jobhunter.dto.LivenessResultDto;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.LivenessStatus;
import dev.jobhunter.repository.JobPostingRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Performs HTTP-based liveness checks on job posting apply URLs.
 * Determines if a job is still active, expired, or uncertain.
 */
@Slf4j
@Service
public class LivenessCheckService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final List<String> DEFAULT_EXPIRED_INDICATORS = List.of(
            "no longer accepting",
            "position filled",
            "position has been filled",
            "job has been filled",
            "no longer available",
            "this position is closed",
            "this job is closed",
            "listing has expired",
            "job expired",
            "applications closed",
            "no longer accepting applications",
            "this role has been filled"
    );

    private static final List<String> ACTIVE_INDICATORS = List.of(
            "apply now",
            "apply for this job",
            "submit application",
            "apply to this position",
            "apply for this position",
            "submit your application",
            "apply here"
    );

    private final JobPostingRepository jobPostingRepository;
    private final EntityManager entityManager;
    private final WebClient webClient;
    private final List<String> expiredIndicators;

    public LivenessCheckService(JobPostingRepository jobPostingRepository,
                                EntityManager entityManager,
                                WebClient webClient) {
        this.jobPostingRepository = jobPostingRepository;
        this.entityManager = entityManager;
        this.webClient = webClient;
        this.expiredIndicators = DEFAULT_EXPIRED_INDICATORS;
    }

    /**
     * Check liveness of a job by its ID. Fetches the applyUrl via HTTP and determines status.
     */
    @Transactional
    public LivenessResultDto checkLiveness(UUID jobId) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return new LivenessResultDto(jobId, LivenessStatus.UNCERTAIN.name(),
                    LocalDateTime.now(), null, "Job not found");
        }

        JobPosting job = jobOpt.get();
        String url = job.getApplyUrl();
        if (url == null || url.isBlank()) {
            LivenessStatus status = LivenessStatus.UNCERTAIN;
            LocalDateTime now = LocalDateTime.now();
            updateLivenessColumns(jobId, status, now);
            return new LivenessResultDto(jobId, status.name(), now, null, "No apply URL available");
        }

        return performCheck(jobId, url);
    }

    /**
     * Check liveness by URL directly (without loading a specific job).
     */
    public LivenessResultDto checkLiveness(String url) {
        if (url == null || url.isBlank()) {
            return new LivenessResultDto(null, LivenessStatus.UNCERTAIN.name(),
                    LocalDateTime.now(), url, "URL is empty");
        }

        // Try to find a job with this URL to associate the result
        Optional<JobPosting> jobOpt = jobPostingRepository.findFirstByApplyUrl(url);
        UUID jobId = jobOpt.map(JobPosting::getId).orElse(null);

        LivenessResultDto result = performHttpCheck(jobId, url);

        // If we found a matching job, persist the status
        if (jobId != null) {
            updateLivenessColumns(jobId, LivenessStatus.valueOf(result.status()), result.checkedAt());
        }

        return result;
    }

    /**
     * Get current liveness status without performing a new check.
     */
    public LivenessResultDto getStatus(UUID jobId) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return new LivenessResultDto(jobId, null, null, null, "Job not found");
        }

        JobPosting job = jobOpt.get();
        Object[] row = (Object[]) entityManager.createNativeQuery(
                "SELECT liveness_status, last_liveness_check FROM job_posting WHERE id = :id")
                .setParameter("id", jobId)
                .getSingleResult();

        String status = (String) row[0];
        java.sql.Timestamp timestamp = (java.sql.Timestamp) row[1];
        LocalDateTime checkedAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new LivenessResultDto(jobId, status, checkedAt, job.getApplyUrl(), null);
    }

    private LivenessResultDto performCheck(UUID jobId, String url) {
        LivenessResultDto result = performHttpCheck(jobId, url);
        updateLivenessColumns(jobId, LivenessStatus.valueOf(result.status()), result.checkedAt());
        return result;
    }

    private LivenessResultDto performHttpCheck(UUID jobId, String url) {
        LocalDateTime now = LocalDateTime.now();

        try {
            // First attempt HEAD request
            var headResponse = webClient.head()
                    .uri(url)
                    .exchangeToMono(response -> Mono.just(response.statusCode().value()))
                    .timeout(REQUEST_TIMEOUT)
                    .onErrorReturn(-1)
                    .block();

            if (headResponse != null && (headResponse == 404 || headResponse == 410)) {
                return new LivenessResultDto(jobId, LivenessStatus.EXPIRED.name(),
                        now, url, "HTTP " + headResponse);
            }

            // GET request to check body content
            String body = webClient.get()
                    .uri(url)
                    .exchangeToMono(response -> {
                        int statusCode = response.statusCode().value();
                        if (statusCode == 404 || statusCode == 410) {
                            return Mono.just("__STATUS_" + statusCode);
                        }
                        if (statusCode >= 400) {
                            return Mono.just("__STATUS_" + statusCode);
                        }
                        return response.bodyToMono(String.class).defaultIfEmpty("");
                    })
                    .timeout(REQUEST_TIMEOUT)
                    .onErrorReturn("")
                    .block();

            if (body == null) {
                return new LivenessResultDto(jobId, LivenessStatus.UNCERTAIN.name(),
                        now, url, "No response body");
            }

            // Check for status-based expiry from GET
            if (body.startsWith("__STATUS_404") || body.startsWith("__STATUS_410")) {
                return new LivenessResultDto(jobId, LivenessStatus.EXPIRED.name(),
                        now, url, "HTTP " + body.replace("__STATUS_", ""));
            }

            if (body.startsWith("__STATUS_")) {
                return new LivenessResultDto(jobId, LivenessStatus.UNCERTAIN.name(),
                        now, url, "HTTP " + body.replace("__STATUS_", ""));
            }

            String lowerBody = body.toLowerCase();

            // Check for expired indicators
            for (String indicator : expiredIndicators) {
                if (lowerBody.contains(indicator)) {
                    return new LivenessResultDto(jobId, LivenessStatus.EXPIRED.name(),
                            now, url, "Page contains: " + indicator);
                }
            }

            // Check for active indicators
            for (String indicator : ACTIVE_INDICATORS) {
                if (lowerBody.contains(indicator)) {
                    return new LivenessResultDto(jobId, LivenessStatus.ACTIVE.name(),
                            now, url, "Page contains apply button");
                }
            }

            // Neither clear signal found
            return new LivenessResultDto(jobId, LivenessStatus.UNCERTAIN.name(),
                    now, url, "No clear expired or active signals detected");

        } catch (Exception e) {
            log.warn("Liveness check failed for URL {}: {}", url, e.getMessage());
            return new LivenessResultDto(jobId, LivenessStatus.UNCERTAIN.name(),
                    now, url, "Check failed: " + e.getMessage());
        }
    }

    private void updateLivenessColumns(UUID jobId, LivenessStatus status, LocalDateTime checkedAt) {
        entityManager.createNativeQuery(
                "UPDATE job_posting SET liveness_status = :status, last_liveness_check = :checkedAt WHERE id = :id")
                .setParameter("status", status.name())
                .setParameter("checkedAt", checkedAt)
                .setParameter("id", jobId)
                .executeUpdate();
    }
}
