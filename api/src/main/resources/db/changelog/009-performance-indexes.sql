--liquibase formatted sql

--changeset jobhunter:009-idx-job-endpoint-active
CREATE INDEX IF NOT EXISTS idx_job_endpoint_active ON job_posting(endpoint_id, is_active);

--changeset jobhunter:009-idx-job-visa-pending
CREATE INDEX IF NOT EXISTS idx_job_visa_pending ON job_posting(visa_sponsorship, discovered_date) WHERE visa_sponsorship = 'PENDING';

--changeset jobhunter:009-idx-job-source-active
CREATE INDEX IF NOT EXISTS idx_job_source_active ON job_posting(source, is_active, language_filter);
