package dev.jobhunter.ingestion;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Enriches aggregator-sourced jobs (excluding LinkedIn) that have short/stub descriptions
 * by fetching the full job page from applyUrl and extracting text via Jsoup.
 */
@Slf4j
@Order(2)
@Component
@ConditionalOnProperty(prefix = "aggregator.enrichment", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AggregatorDescriptionEnricher implements PostIngestionEnricher {

    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final JobPostingRepository jobPostingRepository;
    private final MatchScoreRepository matchScoreRepository;
    private final DescriptionFilterChain descriptionFilterChain;

    private final int batchSize;
    private final int delayBetweenMs;
    private final int minDescriptionLength;

    public AggregatorDescriptionEnricher(
            WebClient webClient,
            JobPostingRepository jobPostingRepository,
            MatchScoreRepository matchScoreRepository,
            DescriptionFilterChain descriptionFilterChain,
            @Value("${aggregator.enrichment.batch-size:5}") int batchSize,
            @Value("${aggregator.enrichment.delay-between-ms:2000}") int delayBetweenMs,
            @Value("${aggregator.enrichment.min-description-length:500}") int minDescriptionLength) {
        this.webClient = webClient;
        this.jobPostingRepository = jobPostingRepository;
        this.matchScoreRepository = matchScoreRepository;
        this.descriptionFilterChain = descriptionFilterChain;
        this.batchSize = batchSize;
        this.delayBetweenMs = delayBetweenMs;
        this.minDescriptionLength = minDescriptionLength;
    }

    @Override
    public void enrich(JobSource source, int created) {
        if (source == JobSource.LINKEDIN || created <= 0) return;
        if (!source.isAggregator() && source != JobSource.DIRECT) return;
        enrichDescriptions();
    }

    void enrichDescriptions() {
        List<JobSource> sourcesToEnrich = new ArrayList<>(JobSource.aggregators());
        sourcesToEnrich.add(JobSource.DIRECT);

        List<JobPosting> jobs = jobPostingRepository
                .findAggregatorJobsNeedingDescription(sourcesToEnrich, minDescriptionLength);

        if (jobs.isEmpty()) {
            return;
        }

        List<JobPosting> batch = jobs.size() > batchSize
                ? jobs.subList(0, batchSize)
                : jobs;

        int enrichedCount = 0;

        for (JobPosting job : batch) {
            try {
                String html = fetchPage(job.getApplyUrl());
                if (html == null || html.isBlank()) {
                    log.debug("Empty response from applyUrl for job [{}]", job.getExternalId());
                    deactivatePendingVisa(job, "visa: pending - no description available (empty response)");
                    continue;
                }

                String extractedText = extractText(html);
                if (extractedText == null || extractedText.length() < minDescriptionLength || extractedText.length() <= currentDescriptionLength(job)) {
                    deactivatePendingVisa(job, "visa: pending - no description available (no better text)");
                    continue;
                }

                updateJobDescription(job, extractedText);
                enrichedCount++;

                if (delayBetweenMs > 0) {
                    Thread.sleep(delayBetweenMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Aggregator enrichment interrupted after {}/{} jobs", enrichedCount, batch.size());
                break;
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    job.setActive(false);
                    job.setFilterReason("url-dead-" + e.getStatusCode().value());
                    jobPostingRepository.save(job);
                    log.info("Deactivated dead-URL job [{}] (HTTP {}): {}", job.getExternalId(), e.getStatusCode().value(), job.getApplyUrl());
                } else {
                    log.warn("Failed to enrich aggregator job [{}] from {}: {}",
                            job.getExternalId(), job.getApplyUrl(), e.getMessage());
                    deactivatePendingVisa(job, "visa: pending - enrichment failed");
                }
            } catch (Exception e) {
                log.warn("Failed to enrich aggregator job [{}] from {}: {}",
                        job.getExternalId(), job.getApplyUrl(), e.getMessage());
                deactivatePendingVisa(job, "visa: pending - enrichment failed");
            }
        }

        log.info("Enriched aggregator job descriptions: {}/{}", enrichedCount, batch.size());
    }

    @Transactional
    void updateJobDescription(JobPosting job, String extractedText) {
        job.setDescription(extractedText);
        descriptionFilterChain.refilter(job);
        jobPostingRepository.save(job);
        // Delete existing score so job gets rescored with new description
        matchScoreRepository.deleteByJobId(job.getId());
    }

    /**
     * Deactivates a job that is still PENDING visa status when enrichment cannot complete
     * (empty response, no better text, or exception). No-op if visa status is not PENDING.
     */
    @Transactional
    void deactivatePendingVisa(JobPosting job, String reason) {
        if (job.getVisaSponsorship() != VisaSponsorship.PENDING) {
            return;
        }
        job.setVisaSponsorship(VisaSponsorship.UNKNOWN);
        job.setActive(false);
        job.setFilterReason(reason);
        jobPostingRepository.save(job);
        log.debug("Deactivated PENDING visa job [{}]: {}", job.getExternalId(), reason);
    }

    String fetchPage(String url) {
        try {
            return webClient.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(FETCH_TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw e;
            }
            log.warn("HTTP fetch failed for {}: {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("HTTP fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Parses HTML and extracts visible text content, stripping navigation and boilerplate.
     */
    String extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, footer, header, noscript, iframe").remove();

        String text = doc.body() != null ? doc.body().text() : "";
        if (text.isBlank()) {
            return null;
        }

        if (text.length() > MAX_DESCRIPTION_LENGTH) {
            text = text.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        return text;
    }

    private int currentDescriptionLength(JobPosting job) {
        return job.getDescription() != null ? job.getDescription().length() : 0;
    }
}
