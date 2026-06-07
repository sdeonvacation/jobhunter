package dev.jobhunter.ingestion;

public record IngestionStats(
    String sourceName,
    int fetched,
    int enriched,
    int created,
    int filtered,
    int duplicates,
    int errors,
    long elapsedMs
) {}
