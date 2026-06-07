package dev.jobhunter.ingestion;

import dev.jobhunter.source.SourceConfig;

public interface AggregatorIngestionService {

    IngestionStats ingest(SourceConfig source);
}
