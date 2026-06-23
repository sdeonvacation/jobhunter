package dev.jobhunter.ingestion;

/**
 * Contract for steps that run after all DescriptionBackfillers complete.
 * Implementations are discovered by Spring and executed in sequence by CrawlService.
 * Examples: re-scoring, re-indexing, notification dispatch.
 */
public interface BackfillPostProcessor {

    void process();
}
