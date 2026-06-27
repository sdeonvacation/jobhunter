--liquibase formatted sql
--changeset career-ops:013-interview

CREATE TABLE interview_story (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    situation TEXT NOT NULL,
    task TEXT,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    reflection TEXT,
    tags JSONB DEFAULT '[]',
    skills JSONB DEFAULT '[]',
    source_job_id UUID REFERENCES job_posting(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_interview_story_tags ON interview_story USING GIN(tags);

CREATE TABLE interview_prep (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL UNIQUE REFERENCES job_posting(id) ON DELETE CASCADE,
    talking_points JSONB NOT NULL,
    mapped_story_ids JSONB DEFAULT '[]',
    company_research JSONB,
    prepared_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_interview_prep_job_id ON interview_prep(job_id);
