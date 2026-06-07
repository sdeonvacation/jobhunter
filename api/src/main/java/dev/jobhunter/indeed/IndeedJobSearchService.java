package dev.jobhunter.indeed;

import dev.jobhunter.ingestion.AggregatorIngestionService;
import dev.jobhunter.ingestion.IngestionStats;
import dev.jobhunter.source.IndeedSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Searches Indeed for jobs via jobspy-js CLI and creates job postings with source=INDEED.
 *
 * Delegates to AggregatorIngestionService for actual fetch/filter/persist logic.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "discovery.providers.jobspy", name = "enabled", havingValue = "true")
public class IndeedJobSearchService {

    private final AggregatorIngestionService aggregatorIngestionService;
    private final IndeedSource indeedSource;

    public IndeedJobSearchService(AggregatorIngestionService aggregatorIngestionService,
                                  IndeedSource indeedSource) {
        this.aggregatorIngestionService = aggregatorIngestionService;
        this.indeedSource = indeedSource;
    }

    /**
     * Search Indeed for jobs matching profile config, filter, and persist new postings.
     *
     * @return int[]{created, filtered, searches}
     */
    public int[] searchAndCreate() {
        log.info("Indeed search starting via aggregator ingestion pipeline");

        IngestionStats stats = aggregatorIngestionService.ingest(indeedSource);

        log.info("Indeed search complete: created={}, filtered={}, fetched={}",
                stats.created(), stats.filtered(), stats.fetched());

        return new int[]{stats.created(), stats.filtered(), stats.fetched()};
    }
}
