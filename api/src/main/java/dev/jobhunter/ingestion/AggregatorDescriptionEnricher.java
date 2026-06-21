package dev.jobhunter.ingestion;

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
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * Enriches aggregator-sourced jobs (excluding LinkedIn) that have short/stub descriptions
 * by fetching the full job page from applyUrl and extracting text via Jsoup.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aggregator.enrichment", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AggregatorDescriptionEnricher implements PostIngestionEnricher {

    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final JobPostingRepository jobPostingRepository;
    private final MatchScoreRepository matchScoreRepository;
    private final LanguageFilter languageFilter;
    private final VisaDetectionChain visaDetectionChain;

    private final int batchSize;
    private final int delayBetweenMs;
    private final int minDescriptionLength;

    public AggregatorDescriptionEnricher(
            WebClient webClient,
            JobPostingRepository jobPostingRepository,
            MatchScoreRepository matchScoreRepository,
            LanguageFilter languageFilter,
            VisaDetectionChain visaDetectionChain,
            @Value("${aggregator.enrichment.batch-size:5}") int batchSize,
            @Value("${aggregator.enrichment.delay-between-ms:2000}") int delayBetweenMs,
            @Value("${aggregator.enrichment.min-description-length:500}") int minDescriptionLength) {
        this.webClient = webClient;
        this.jobPostingRepository = jobPostingRepository;
        this.matchScoreRepository = matchScoreRepository;
        this.languageFilter = languageFilter;
        this.visaDetectionChain = visaDetectionChain;
        this.batchSize = batchSize;
        this.delayBetweenMs = delayBetweenMs;
        this.minDescriptionLength = minDescriptionLength;
    }

    @Override
    public void enrich(JobSource source, int created) {
        if (source == JobSource.LINKEDIN || !source.isAggregator() || created <= 0) {
            return;
        }
        enrichDescriptions();
    }

    void enrichDescriptions() {
        List<JobSource> aggregatorSources = JobSource.aggregators();

        List<JobPosting> jobs = jobPostingRepository
                .findAggregatorJobsNeedingDescription(aggregatorSources, minDescriptionLength);

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
                    continue;
                }

                String extractedText = extractText(html);
                if (extractedText == null || extractedText.length() <= currentDescriptionLength(job)) {
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
            } catch (Exception e) {
                log.warn("Failed to enrich aggregator job [{}] from {}: {}",
                        job.getExternalId(), job.getApplyUrl(), e.getMessage());
            }
        }

        log.info("Enriched aggregator job descriptions: {}/{}", enrichedCount, batch.size());
    }

    @Transactional
    void updateJobDescription(JobPosting job, String extractedText) {
        job.setDescription(extractedText);

        FilterResult langResult = languageFilter.filter(job.getTitle(), extractedText);
        if (langResult.decision() == FilterDecision.SKIP) {
            job.setLanguageFilter(FilterDecision.SKIP);
            log.debug("Aggregator job [{}] filtered by language after enrichment: {}",
                    job.getExternalId(), langResult.reason());
        }

        // Two-pass visa re-evaluation: resolve PENDING status now that full description is available
        if (job.getVisaSponsorship() == VisaSponsorship.PENDING) {
            VisaDetectionResult visaResult = visaDetectionChain.evaluate(extractedText);
            switch (visaResult.status()) {
                case CONFIRMED, LIKELY -> job.setVisaSponsorship(visaResult.status());
                case REJECTED, UNKNOWN -> {
                    job.setVisaSponsorship(visaResult.status());
                    job.setActive(false);
                    job.setFilterReason("visa: " + visaResult.reason());
                    log.debug("Aggregator job [{}] deactivated after visa re-evaluation: {}",
                            job.getExternalId(), visaResult.reason());
                }
                default -> job.setVisaSponsorship(visaResult.status());
            }
        }

        jobPostingRepository.save(job);

        // Delete existing score so job gets rescored with new description
        matchScoreRepository.deleteByJobId(job.getId());
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
