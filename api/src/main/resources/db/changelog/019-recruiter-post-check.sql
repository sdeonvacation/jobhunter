--liquibase formatted sql
--changeset jobhunter:019-recruiter-post-check

-- Cache table for recruiter post detection verdicts per job URL
CREATE TABLE recruiter_post_check (
    id UUID PRIMARY KEY,
    job_url VARCHAR(2048) NOT NULL UNIQUE,
    verdict VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    result_data JSONB,
    checked_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_recruiter_post_check_expires_at ON recruiter_post_check (expires_at);

--rollback DROP TABLE recruiter_post_check;