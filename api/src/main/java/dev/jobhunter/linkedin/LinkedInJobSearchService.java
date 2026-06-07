package dev.jobhunter.linkedin;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.source.LinkedInSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Searches LinkedIn for jobs matching profile skills, then enriches existing ATS jobs
 * with LinkedIn links or creates new LinkedIn-source job postings.
 *
 * Delegates to AggregatorIngestionService for actual fetch/filter/persist logic.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInJobSearchService {

    private final AggregatorIngestionService aggregatorIngestionService;
    private final LinkedInSource linkedInSource;

    public LinkedInJobSearchService(AggregatorIngestionService aggregatorIngestionService,
                                    LinkedInSource linkedInSource) {
        this.aggregatorIngestionService = aggregatorIngestionService;
        this.linkedInSource = linkedInSource;
    }

    /**
     * Search LinkedIn for jobs matching profile skills, then:
     * - Match against existing ATS jobs by company name + title similarity
     * - If match found: add "linkedin" entry to external_links
     * - If no match: create new JobPosting with source=LINKEDIN
     *
     * @return int[]{enriched, newlyCreated, searched}
     */
    public int[] searchAndMatch() {
        log.info("LinkedIn search starting via aggregator ingestion pipeline");

        IngestionStats stats = aggregatorIngestionService.ingest(linkedInSource);

        log.info("LinkedIn search complete: {} enriched, {} created, {} fetched",
                stats.enriched(), stats.created(), stats.fetched());

        return new int[]{stats.enriched(), stats.created(), stats.fetched()};
    }
}
