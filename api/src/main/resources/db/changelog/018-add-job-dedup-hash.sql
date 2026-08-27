--liquibase formatted sql
--changeset jobhunter:018-add-job-dedup-hash

-- L5 cross-source dedup: SHA-256 hex of normalized applyUrl.
-- Nullable for backward compatibility with existing rows.
ALTER TABLE job_posting ADD COLUMN dedup_hash VARCHAR(64);

-- Partial index: only non-null hashes are dedup candidates.
CREATE INDEX IF NOT EXISTS idx_job_posting_dedup_hash ON job_posting(dedup_hash) WHERE dedup_hash IS NOT NULL;

--rollback DROP INDEX IF EXISTS idx_job_posting_dedup_hash;
--rollback ALTER TABLE job_posting DROP COLUMN dedup_hash;
