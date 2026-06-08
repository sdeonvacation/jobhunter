package dev.jobhunter.ingestion;

import dev.jobhunter.model.enums.JobSource;

/**
 * Hook invoked after aggregator ingestion completes.
 * Implementations decide internally whether to act based on source type and created count.
 */
public interface PostIngestionEnricher {

    void enrich(JobSource source, int created);
}
