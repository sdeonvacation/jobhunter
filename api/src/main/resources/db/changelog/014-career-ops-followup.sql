--liquibase formatted sql
--changeset career-ops:014-followup

CREATE TABLE follow_up (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    scheduled_date DATE NOT NULL,
    sent_date DATE,
    count INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_follow_up_job_id ON follow_up(job_id);
CREATE INDEX idx_follow_up_status_date ON follow_up(status, scheduled_date)
    WHERE status IN ('PENDING', 'OVERDUE');
