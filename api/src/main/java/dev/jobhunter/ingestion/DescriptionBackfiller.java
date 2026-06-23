package dev.jobhunter.ingestion;

/**
 * Contract for backfilling descriptions on jobs ingested without one.
 * Implementations fetch descriptions for their source, call DescriptionFilterChain.refilter(),
 * and persist. CrawlService discovers all beans and calls backfill() after each crawl cycle.
 */
public interface DescriptionBackfiller {

    void backfill();
}
