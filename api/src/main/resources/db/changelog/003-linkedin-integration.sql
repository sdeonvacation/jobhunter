--liquibase formatted sql

--changeset jobhub:003-1-create-outreach-contact
CREATE TABLE outreach_contact (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    linkedin_url VARCHAR(500) NOT NULL UNIQUE,
    person_name VARCHAR(255) NOT NULL,
    title VARCHAR(500),
    connection_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    last_contacted_at TIMESTAMP,
    connection_sent_at TIMESTAMP,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outreach_company ON outreach_contact(company_id);
--rollback DROP TABLE outreach_contact;

--changeset jobhub:003-2-create-profile-cache
CREATE TABLE profile_cache (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    linkedin_url VARCHAR(500) NOT NULL UNIQUE,
    profile_data JSONB NOT NULL,
    fetched_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_profile_cache_url ON profile_cache(linkedin_url);
CREATE INDEX idx_profile_cache_expires ON profile_cache(expires_at);
--rollback DROP TABLE profile_cache;

--changeset jobhub:003-3-company-linkedin-columns
ALTER TABLE company ADD COLUMN linkedin_url VARCHAR(500);
ALTER TABLE company ADD COLUMN industry VARCHAR(255);
ALTER TABLE company ADD COLUMN employee_count INTEGER;
ALTER TABLE company ADD COLUMN specialties TEXT;
ALTER TABLE company ADD COLUMN recent_posts_summary TEXT;
ALTER TABLE company ADD COLUMN linkedin_enriched_at TIMESTAMP;
--rollback ALTER TABLE company DROP COLUMN linkedin_url, DROP COLUMN industry, DROP COLUMN employee_count, DROP COLUMN specialties, DROP COLUMN recent_posts_summary, DROP COLUMN linkedin_enriched_at;

--changeset jobhub:003-4-job-posting-poster-columns
ALTER TABLE job_posting ADD COLUMN poster_name VARCHAR(255);
ALTER TABLE job_posting ADD COLUMN poster_title VARCHAR(500);
ALTER TABLE job_posting ADD COLUMN poster_linkedin_url VARCHAR(500);
ALTER TABLE job_posting ADD COLUMN poster_avatar_url VARCHAR(500);
ALTER TABLE job_posting ADD COLUMN poster_contact_id UUID REFERENCES outreach_contact(id);
--rollback ALTER TABLE job_posting DROP COLUMN poster_name, DROP COLUMN poster_title, DROP COLUMN poster_linkedin_url, DROP COLUMN poster_avatar_url, DROP COLUMN poster_contact_id;

--changeset jobhub:003-5-job-posting-applied-columns
ALTER TABLE job_posting ADD COLUMN applied BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE job_posting ADD COLUMN applied_at TIMESTAMP;
ALTER TABLE job_posting ADD COLUMN required_yoe INTEGER;
--rollback ALTER TABLE job_posting DROP COLUMN applied, DROP COLUMN applied_at, DROP COLUMN required_yoe;

--changeset jobhub:003-6-career-endpoint-error-tracking
ALTER TABLE career_endpoint ADD COLUMN last_error_message VARCHAR(2000);
ALTER TABLE career_endpoint ADD COLUMN consecutive_errors INTEGER NOT NULL DEFAULT 0;
--rollback ALTER TABLE career_endpoint DROP COLUMN last_error_message, DROP COLUMN consecutive_errors;

--changeset jobhub:003-7-job-posting-external-links
ALTER TABLE job_posting ADD COLUMN external_links JSONB DEFAULT '{}';
--rollback ALTER TABLE job_posting DROP COLUMN external_links;
