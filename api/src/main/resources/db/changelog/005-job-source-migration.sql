--liquibase formatted sql
--changeset jobhub:005-job-source-migration

UPDATE job_posting SET source = 'DIRECT' WHERE source = 'CUSTOM';

UPDATE job_posting SET source = 'BERLIN_STARTUP_JOBS'
WHERE company_id IN (SELECT id FROM company WHERE normalized_name = 'berlin startup jobs')
AND source = 'DIRECT';

CREATE INDEX IF NOT EXISTS idx_job_posting_source ON job_posting(source);

CREATE TABLE aggregator_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_name     VARCHAR(50) NOT NULL UNIQUE,
    last_run_at     TIMESTAMP NOT NULL,
    last_status     VARCHAR(20) NOT NULL,
    jobs_fetched    INTEGER DEFAULT 0,
    jobs_created    INTEGER DEFAULT 0,
    jobs_enriched   INTEGER DEFAULT 0,
    jobs_filtered   INTEGER DEFAULT 0,
    errors          INTEGER DEFAULT 0,
    elapsed_ms      BIGINT DEFAULT 0,
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_aggregator_run_source ON aggregator_run(source_name);
