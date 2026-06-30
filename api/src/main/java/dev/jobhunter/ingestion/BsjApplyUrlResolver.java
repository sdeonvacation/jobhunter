package dev.jobhunter.ingestion;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * Resolves the real company ATS apply URL from Berlin Startup Jobs listing pages.
 * BSJ pages embed the actual ATS link in an "Apply now" button — this enricher
 * extracts that link and overwrites the berlinstartupjobs.com URL so that downstream
 * enrichers (e.g. AggregatorDescriptionEnricher) fetch from the real ATS directly.
 */
@Slf4j
@Order(1)
@Component
public class BsjApplyUrlResolver implements PostIngestionEnricher {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final JobPostingRepository jobPostingRepository;

    public BsjApplyUrlResolver(WebClient webClient, JobPostingRepository jobPostingRepository) {
        this.webClient = webClient;
        this.jobPostingRepository = jobPostingRepository;
    }

    @Override
    public void enrich(JobSource source, int created) {
        if (source != JobSource.BERLIN_STARTUP_JOBS) {
            return;
        }
        resolveApplyUrls();
    }

    void resolveApplyUrls() {
        List<JobPosting> jobs = jobPostingRepository.findJobsWithUnresolvedBsjUrl(JobSource.BERLIN_STARTUP_JOBS);
        if (jobs.isEmpty()) {
            return;
        }
        log.info("Resolving BSJ apply URLs for {} jobs", jobs.size());
        int resolved = 0;
        for (JobPosting job : jobs) {
            try {
                String html = fetchPage(job.getApplyUrl());
                if (html == null || html.isBlank()) {
                    log.debug("Empty response from BSJ page for job [{}]", job.getExternalId());
                    continue;
                }
                String realUrl = extractApplyUrl(html, job.getApplyUrl());
                if (realUrl != null) {
                    log.info("Resolved BSJ apply URL [{}]: {} -> {}", job.getExternalId(), job.getApplyUrl(), realUrl);
                    job.setApplyUrl(realUrl);
                    jobPostingRepository.save(job);
                    resolved++;
                } else {
                    log.debug("No external apply URL found on BSJ page for job [{}]", job.getExternalId());
                }
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    log.debug("BSJ page gone (HTTP {}) for job [{}] - will be handled by description enricher",
                            e.getStatusCode().value(), job.getExternalId());
                } else {
                    log.warn("Failed to fetch BSJ page for job [{}]: {}", job.getExternalId(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve BSJ apply URL for job [{}]: {}", job.getExternalId(), e.getMessage());
            }
        }
        log.info("Resolved BSJ apply URLs: {}/{}", resolved, jobs.size());
    }

    private String extractApplyUrl(String html, String pageUrl) {
        try {
            Document doc = Jsoup.parse(html, pageUrl);
            // Primary: BSJ uses "button--orange" class for the Apply CTA
            for (String selector : List.of(
                    "a.button--orange[href]",
                    "a[href]:containsOwn(Apply now)",
                    "a[href]:containsOwn(Apply for this)")) {
                Element el = doc.selectFirst(selector);
                if (el != null) {
                    String href = el.absUrl("href");
                    if (!href.isBlank() && !href.contains("berlinstartupjobs.com")) {
                        return href;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing BSJ page HTML: {}", e.getMessage());
        }
        return null;
    }

    private String fetchPage(String url) {
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
    }
}
