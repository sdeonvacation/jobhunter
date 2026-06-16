--liquibase formatted sql

--changeset jobhub:007-1-extend-outreach-contact
ALTER TABLE outreach_contact ADD COLUMN seniority VARCHAR(20);
ALTER TABLE outreach_contact ADD COLUMN discovered_via VARCHAR(20) DEFAULT 'MANUAL';
ALTER TABLE outreach_contact ADD COLUMN location VARCHAR(255);
ALTER TABLE outreach_contact ADD COLUMN tech_stack JSONB;
ALTER TABLE outreach_contact ADD COLUMN interview_generation_weight INTEGER DEFAULT 0;
ALTER TABLE outreach_contact ADD COLUMN warmth_score INTEGER DEFAULT 0;
ALTER TABLE outreach_contact ADD COLUMN contact_priority_score INTEGER DEFAULT 0;
--rollback ALTER TABLE outreach_contact DROP COLUMN seniority, DROP COLUMN discovered_via, DROP COLUMN location, DROP COLUMN tech_stack, DROP COLUMN interview_generation_weight, DROP COLUMN warmth_score, DROP COLUMN contact_priority_score;

--changeset jobhub:007-2-create-relationship
CREATE TABLE relationship (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id UUID NOT NULL UNIQUE REFERENCES outreach_contact(id),
    status VARCHAR(30) NOT NULL DEFAULT 'DISCOVERED',
    last_contact_at TIMESTAMP,
    last_reply_at TIMESTAMP,
    response_rate DOUBLE PRECISION DEFAULT 0.0,
    referral_requested BOOLEAN DEFAULT FALSE,
    referred BOOLEAN DEFAULT FALSE,
    interview_obtained BOOLEAN DEFAULT FALSE,
    referred_by_contact_id UUID REFERENCES outreach_contact(id),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_relationship_status ON relationship(status);
CREATE INDEX idx_relationship_contact ON relationship(contact_id);
--rollback DROP TABLE relationship;

--changeset jobhub:007-3-create-relationship-event
CREATE TABLE relationship_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id UUID NOT NULL REFERENCES relationship(id),
    event_type VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW(),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rel_event_relationship ON relationship_event(relationship_id, occurred_at DESC);
--rollback DROP TABLE relationship_event;

--changeset jobhub:007-4-create-outreach-message
CREATE TABLE outreach_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id UUID NOT NULL REFERENCES outreach_contact(id),
    direction VARCHAR(5) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content TEXT,
    sent_at TIMESTAMP NOT NULL DEFAULT NOW(),
    replied BOOLEAN DEFAULT FALSE,
    replied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outreach_msg_contact ON outreach_message(contact_id, sent_at DESC);
--rollback DROP TABLE outreach_message;

--changeset jobhub:007-5-create-contact-discovery-run
CREATE TABLE contact_discovery_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES company(id),
    source VARCHAR(20) NOT NULL,
    contacts_found INTEGER NOT NULL DEFAULT 0,
    contacts_new INTEGER NOT NULL DEFAULT 0,
    run_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_discovery_run_company ON contact_discovery_run(company_id, run_at DESC);
--rollback DROP TABLE contact_discovery_run;

--changeset jobhub:007-6-add-interview-source
ALTER TABLE application ADD COLUMN interview_source VARCHAR(20);
--rollback ALTER TABLE application DROP COLUMN interview_source;

--changeset jobhub:007-7-extend-outreach-message-ai
ALTER TABLE outreach_message ADD COLUMN template_used VARCHAR(30);
ALTER TABLE outreach_message ADD COLUMN ai_generated BOOLEAN DEFAULT FALSE;
ALTER TABLE outreach_message ADD COLUMN tokens_used INTEGER DEFAULT 0;
--rollback ALTER TABLE outreach_message DROP COLUMN template_used, DROP COLUMN ai_generated, DROP COLUMN tokens_used;

--changeset jobhub:007-8-pipeline-indexes
CREATE INDEX IF NOT EXISTS idx_application_interview_source ON application(interview_source);
CREATE INDEX IF NOT EXISTS idx_application_status_date ON application(status, applied_date);
--rollback DROP INDEX IF EXISTS idx_application_interview_source; DROP INDEX IF EXISTS idx_application_status_date;

--changeset jobhub:007-9-company-intelligence
ALTER TABLE company ADD COLUMN hiring_velocity INTEGER;
ALTER TABLE company ADD COLUMN employee_growth VARCHAR(50);
ALTER TABLE company ADD COLUMN funding_stage VARCHAR(50);
ALTER TABLE company ADD COLUMN has_sponsored_before BOOLEAN;
ALTER TABLE company ADD COLUMN english_speaking BOOLEAN;
ALTER TABLE company ADD COLUMN international_workforce BOOLEAN;
ALTER TABLE company ADD COLUMN visa_friendliness VARCHAR(10) DEFAULT 'UNKNOWN';
--rollback ALTER TABLE company DROP COLUMN hiring_velocity, DROP COLUMN employee_growth, DROP COLUMN funding_stage, DROP COLUMN has_sponsored_before, DROP COLUMN english_speaking, DROP COLUMN international_workforce, DROP COLUMN visa_friendliness;
