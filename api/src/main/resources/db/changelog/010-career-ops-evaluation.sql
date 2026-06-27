--liquibase formatted sql
--changeset career-ops:010-evaluation

CREATE TABLE job_evaluation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL UNIQUE REFERENCES job_posting(id) ON DELETE CASCADE,
    role_summary JSONB,
    cv_match JSONB,
    level_strategy JSONB,
    comp_research JSONB,
    customization_plan JSONB,
    interview_plan JSONB,
    legitimacy JSONB,
    overall_score INTEGER NOT NULL CHECK (overall_score BETWEEN 1 AND 5),
    archetype VARCHAR(50),
    legitimacy_tier VARCHAR(10),
    description_fingerprint VARCHAR(64),
    evaluated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_job_evaluation_job_id ON job_evaluation(job_id);
CREATE INDEX idx_job_evaluation_score ON job_evaluation(overall_score DESC);
