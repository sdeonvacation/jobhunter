package dev.jobhunter.service;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.ingestion.DescriptionBackfiller;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * Generic backfiller for jobs that have an apply_url but no description.
 * Fetches the apply URL page, extracts description from JSON-LD or meta tags.
 * Works across all sources (JOBGETHER, DIRECT, BUILTIN_EUROPE, etc.).
 */
@Slf4j
@Service
public class ApplyUrlDescriptionBackfiller implements DescriptionBackfiller {

    private static final int BATCH_SIZE = 100;
    private static final int FETCH_TIMEOUT_MS = 15_000;
    private static final int DELAY_BETWEEN_FETCHES_MS = 300;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final JobPostingRepository jobPostingRepository;
    private final DescriptionFilterChain descriptionFilterChain;
    private final MatchScoreRepository matchScoreRepository;
    private final ObjectMapper objectMapper;

    public ApplyUrlDescriptionBackfiller(JobPostingRepository jobPostingRepository,
                                         DescriptionFilterChain descriptionFilterChain,
                                         MatchScoreRepository matchScoreRepository,
                                         ObjectMapper objectMapper) {
        this.jobPostingRepository = jobPostingRepository;
        this.descriptionFilterChain = descriptionFilterChain;
        this.matchScoreRepository = matchScoreRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void backfill() {
        List<JobPosting> jobs = jobPostingRepository
                .findActiveKeepJobsWithApplyUrlButNoDescription(FilterDecision.KEEP, PageRequest.of(0, BATCH_SIZE));

        if (jobs.isEmpty()) return;

        log.info("ApplyUrl backfill: {} jobs with apply_url but no description", jobs.size());
        int filled = 0;
        int errors = 0;

        for (JobPosting job : jobs) {
            String applyUrl = job.getApplyUrl();
            if (applyUrl == null || applyUrl.isBlank()) continue;

            try {
                String description = fetchDescriptionFromUrl(applyUrl);
                if (description != null && description.length() > 50) {
                    job.setDescription(description);
                    descriptionFilterChain.refilter(job);
                    jobPostingRepository.save(job);
                    matchScoreRepository.deleteByJobId(job.getId());
                    filled++;
                    log.debug("ApplyUrl backfill: filled {} ({}) from {}", job.getId(), job.getTitle(), applyUrl);
                }

                Thread.sleep(DELAY_BETWEEN_FETCHES_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("ApplyUrl backfill interrupted after {}/{}", filled, jobs.size());
                break;
            } catch (Exception e) {
                errors++;
                log.debug("ApplyUrl backfill: failed for {} ({}): {}", job.getId(), applyUrl, e.getMessage());
            }
        }

        log.info("ApplyUrl backfill: {}/{} descriptions filled, {} errors", filled, jobs.size(), errors);
    }

    /**
     * Fetches a page and extracts the job description from JSON-LD or meta tags.
     */
    private String fetchDescriptionFromUrl(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(FETCH_TIMEOUT_MS)
                .followRedirects(true)
                .get();

        // Try JSON-LD first (most reliable)
        String description = extractFromJsonLd(doc);
        if (description != null) return description;

        // Fallback: meta description (usually too short, but better than nothing)
        Element metaDesc = doc.selectFirst("meta[name=description]");
        if (metaDesc != null) {
            String content = metaDesc.attr("content");
            if (content != null && content.length() > 100) {
                return content;
            }
        }

        return null;
    }

    private String extractFromJsonLd(Document doc) {
        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element script : scripts) {
            try {
                String json = script.data();
                if (json.isBlank()) continue;

                JsonNode node = objectMapper.readTree(json);
                // Handle @graph arrays (some sites wrap in array)
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        String desc = extractDescriptionFromNode(item);
                        if (desc != null) return desc;
                    }
                } else {
                    String desc = extractDescriptionFromNode(node);
                    if (desc != null) return desc;
                }
            } catch (Exception e) {
                // Not valid JSON or unexpected structure — try next script block
            }
        }
        return null;
    }

    private String extractDescriptionFromNode(JsonNode node) {
        // Check @type is JobPosting or similar
        JsonNode typeNode = node.get("@type");
        if (typeNode != null) {
            String type = typeNode.asText();
            if (!"JobPosting".equals(type) && !"JobListing".equals(type)) {
                return null;
            }
        }

        JsonNode descNode = node.get("description");
        if (descNode != null && !descNode.isNull()) {
            String desc = descNode.asText();
            if (desc != null && desc.length() > 50) {
                return desc;
            }
        }
        return null;
    }
}
