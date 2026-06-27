--liquibase formatted sql
--changeset career-ops:011-cover-letter

CREATE TABLE cover_letter (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    tone VARCHAR(30) NOT NULL DEFAULT 'professional',
    focus VARCHAR(255),
    angles JSONB,
    keywords_mirrored JSONB,
    version INTEGER NOT NULL DEFAULT 1,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    edited_at TIMESTAMP
);

CREATE INDEX idx_cover_letter_job_id ON cover_letter(job_id);
