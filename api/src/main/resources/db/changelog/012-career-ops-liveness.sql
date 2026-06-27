--liquibase formatted sql
--changeset career-ops:012-liveness

ALTER TABLE job_posting ADD COLUMN liveness_status VARCHAR(20);
ALTER TABLE job_posting ADD COLUMN last_liveness_check TIMESTAMP;

CREATE INDEX idx_job_posting_liveness ON job_posting(liveness_status)
    WHERE liveness_status IS NOT NULL;
