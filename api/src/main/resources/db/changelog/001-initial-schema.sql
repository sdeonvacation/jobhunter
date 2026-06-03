--liquibase formatted sql
--changeset jobhub:001-initial-schema

-- Company registry
CREATE TABLE company (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    domain          VARCHAR(255),
    country         VARCHAR(100),
    is_active       BOOLEAN DEFAULT true,
    status          VARCHAR(50) NOT NULL DEFAULT 'DISCOVERED',
    discovered_via  VARCHAR(50) NOT NULL,
    discovered_at   TIMESTAMP NOT NULL DEFAULT now(),
    avg_match_score INTEGER,
    interview_rate  FLOAT DEFAULT 0,
    total_applications INTEGER DEFAULT 0,
    total_interviews   INTEGER DEFAULT 0,
    priority_score  FLOAT DEFAULT 50,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_company_normalized_name ON company(normalized_name);
CREATE INDEX idx_company_status ON company(status);
CREATE INDEX idx_company_priority ON company(priority_score DESC);

-- Career endpoints
CREATE TABLE career_endpoint (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id           UUID NOT NULL REFERENCES company(id),
    url                  VARCHAR(1024) NOT NULL,
    ats_type             VARCHAR(50) NOT NULL,
    ats_slug             VARCHAR(255),
    ats_shard_id         VARCHAR(10),
    extraction_method    VARCHAR(50) NOT NULL DEFAULT 'CUSTOM',
    confidence           VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    verified             BOOLEAN DEFAULT false,
    is_active            BOOLEAN DEFAULT true,
    last_crawl_status    VARCHAR(50),
    last_crawled_at      TIMESTAMP,
    crawl_frequency_hours INTEGER DEFAULT 4,
    source               VARCHAR(255),
    created_at           TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_endpoint_company ON career_endpoint(company_id);
CREATE INDEX idx_endpoint_active ON career_endpoint(is_active, ats_type);
CREATE INDEX idx_endpoint_next_crawl ON career_endpoint(is_active, last_crawled_at);

-- Job postings
CREATE TABLE job_posting (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source            VARCHAR(50) NOT NULL,
    endpoint_id       UUID REFERENCES career_endpoint(id),
    external_id       VARCHAR(255) NOT NULL,
    title             VARCHAR(500) NOT NULL,
    company_id        UUID NOT NULL REFERENCES company(id),
    location          VARCHAR(500),
    location_city     VARCHAR(255),
    location_country  VARCHAR(100),
    is_remote         VARCHAR(20) DEFAULT 'ONSITE',
    description       TEXT,
    apply_url         VARCHAR(2048),
    posted_date       DATE,
    discovered_date   DATE NOT NULL DEFAULT CURRENT_DATE,
    employment_type   VARCHAR(50) DEFAULT 'FULL_TIME',
    salary_min        NUMERIC(12,2),
    salary_max        NUMERIC(12,2),
    salary_currency   VARCHAR(3),
    salary_period     VARCHAR(20),
    raw_content       JSONB,
    is_active         BOOLEAN DEFAULT true,
    deactivated_at    TIMESTAMP,
    fingerprint       VARCHAR(64),
    language_filter   VARCHAR(20) DEFAULT 'KEEP',
    filter_reason     VARCHAR(255),
    recruiter_name    VARCHAR(255),
    recruiter_email   VARCHAR(255),
    recruiter_data_expires_at TIMESTAMP,
    last_crawled_at   TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_job_source_external ON job_posting(source, external_id);
CREATE INDEX idx_job_company ON job_posting(company_id);
CREATE INDEX idx_job_active ON job_posting(is_active, language_filter);
CREATE INDEX idx_job_discovered ON job_posting(discovered_date DESC);
CREATE INDEX idx_job_fingerprint ON job_posting(fingerprint);
CREATE INDEX idx_job_recruiter_expiry ON job_posting(recruiter_data_expires_at) WHERE recruiter_data_expires_at IS NOT NULL;

-- Job skills
CREATE TABLE job_skill (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    skill_name  VARCHAR(100) NOT NULL,
    category    VARCHAR(50) NOT NULL,
    is_required BOOLEAN DEFAULT true,
    raw_mention VARCHAR(255)
);
CREATE INDEX idx_skill_job ON job_skill(job_id);
CREATE INDEX idx_skill_name ON job_skill(skill_name);

-- Match scores
CREATE TABLE match_score (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    overall_score   INTEGER NOT NULL,
    matched_skills  JSONB NOT NULL,
    missing_skills  JSONB NOT NULL,
    recommendation  VARCHAR(20) NOT NULL,
    scored_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_match_job ON match_score(job_id);

-- Opportunity scores
CREATE TABLE opportunity_score (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    score       INTEGER NOT NULL,
    breakdown   JSONB NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_opp_job ON opportunity_score(job_id);
CREATE INDEX idx_opp_score ON opportunity_score(score DESC);

-- Applications
CREATE TABLE application (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES job_posting(id),
    status          VARCHAR(50) NOT NULL DEFAULT 'INTERESTED',
    applied_date    DATE,
    notes           TEXT,
    resume_variant  VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_application_job ON application(job_id);
CREATE INDEX idx_application_status ON application(status);

-- Outcomes
CREATE TABLE job_outcome (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id  UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    stage           VARCHAR(50) NOT NULL,
    occurred_at     DATE NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outcome_application ON job_outcome(application_id);

-- Discovery events
CREATE TABLE discovery_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID REFERENCES company(id),
    company_name    VARCHAR(255) NOT NULL,
    provider        VARCHAR(100) NOT NULL,
    source_job_title VARCHAR(500),
    source_url      VARCHAR(2048),
    discovered_at   TIMESTAMP NOT NULL DEFAULT now(),
    outcome         VARCHAR(50) NOT NULL
);
CREATE INDEX idx_discovery_provider ON discovery_event(provider, discovered_at DESC);

-- Resolution results
CREATE TABLE resolution_result (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id          UUID NOT NULL REFERENCES company(id),
    strategy            VARCHAR(50) NOT NULL,
    candidate_urls      JSONB NOT NULL,
    selected_url        VARCHAR(2048),
    confidence          VARCHAR(20) NOT NULL,
    ambiguity_reason    VARCHAR(500),
    resolved_at         TIMESTAMP NOT NULL DEFAULT now(),
    needs_manual_review BOOLEAN DEFAULT false
);
CREATE INDEX idx_resolution_company ON resolution_result(company_id);
CREATE INDEX idx_resolution_review ON resolution_result(needs_manual_review) WHERE needs_manual_review = true;
