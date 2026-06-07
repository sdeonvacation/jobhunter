--liquibase formatted sql
--changeset jobhub:004-fix-migrated-endpoints

-- ============================================================================
-- Fix endpoints where companies migrated to different ATS platforms
-- ============================================================================

-- Celonis: LEVER -> GREENHOUSE (193 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'GREENHOUSE', ats_slug = 'celonis',
    url = 'https://boards.greenhouse.io/celonis',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000002-0000-0000-0000-000000000002';

-- Delivery Hero: GREENHOUSE -> SMARTRECRUITERS (1080 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'SMARTRECRUITERS', ats_slug = 'DeliveryHero',
    url = 'https://careers.smartrecruiters.com/DeliveryHero',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000004-0000-0000-0000-000000000004';

-- Scalable Capital: LEVER -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Lever; no supported ATS found'
WHERE company_id = 'a0000007-0000-0000-0000-000000000007';

-- JetBrains: LEVER -> GREENHOUSE (107 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'GREENHOUSE', ats_slug = 'jetbrains',
    url = 'https://boards.greenhouse.io/jetbrains',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000009-0000-0000-0000-000000000009';

-- Auto1 Group: GREENHOUSE -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Greenhouse; no supported ATS found'
WHERE company_id = 'a0000011-0000-0000-0000-000000000011';

-- Flix: LEVER -> GREENHOUSE (137 jobs confirmed, slug is 'flix' not 'flixbus')
UPDATE career_endpoint
SET ats_type = 'GREENHOUSE', ats_slug = 'flix',
    url = 'https://boards.greenhouse.io/flix',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000012-0000-0000-0000-000000000012';

-- Wefox: GREENHOUSE -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Greenhouse; no supported ATS found'
WHERE company_id = 'a0000014-0000-0000-0000-000000000014';

-- Mambu: GREENHOUSE -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Greenhouse; no supported ATS found'
WHERE company_id = 'a0000015-0000-0000-0000-000000000015';

-- Mollie: GREENHOUSE -> ASHBY (50 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'ASHBY', ats_slug = 'mollie',
    url = 'https://jobs.ashbyhq.com/mollie',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000016-0000-0000-0000-000000000016';

-- Klarna: GREENHOUSE -> deactivate (custom platform, no supported ATS)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company uses custom career platform at klarna.com/careers'
WHERE company_id = 'a0000019-0000-0000-0000-000000000019';

-- Miro: GREENHOUSE slug 'miro' -> update to 'realtimeboardglobal' (confirmed working)
UPDATE career_endpoint
SET ats_slug = 'realtimeboardglobal',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000022-0000-0000-0000-000000000022';

-- Confluent: GREENHOUSE -> ASHBY (48 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'ASHBY', ats_slug = 'confluent',
    url = 'https://jobs.ashbyhq.com/confluent',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000027-0000-0000-0000-000000000027';

-- HashiCorp: GREENHOUSE -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Greenhouse; no supported ATS found'
WHERE company_id = 'a0000028-0000-0000-0000-000000000028';

-- Snyk: GREENHOUSE -> ASHBY (slug confirmed, currently 0 jobs but board exists)
UPDATE career_endpoint
SET ats_type = 'ASHBY', ats_slug = 'snyk',
    url = 'https://jobs.ashbyhq.com/snyk',
    extraction_method = 'STRUCTURED_API', confidence = 'MEDIUM',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000030-0000-0000-0000-000000000030';

-- Sentry: GREENHOUSE -> ASHBY (47 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'ASHBY', ats_slug = 'sentry',
    url = 'https://jobs.ashbyhq.com/sentry',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000032-0000-0000-0000-000000000032';

-- Supabase: GREENHOUSE -> ASHBY (47 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'ASHBY', ats_slug = 'supabase',
    url = 'https://jobs.ashbyhq.com/supabase',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000035-0000-0000-0000-000000000035';

-- Wise: GREENHOUSE -> SMARTRECRUITERS (356 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'SMARTRECRUITERS', ats_slug = 'Wise',
    url = 'https://careers.smartrecruiters.com/Wise',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000037-0000-0000-0000-000000000037';

-- Revolut: LEVER -> deactivate (custom platform at revolut.com/careers)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company uses custom career platform at revolut.com/careers'
WHERE company_id = 'a0000038-0000-0000-0000-000000000038';

-- Thought Machine: GREENHOUSE -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Greenhouse; no supported ATS found'
WHERE company_id = 'a0000041-0000-0000-0000-000000000041';

-- Plaid: GREENHOUSE -> ASHBY (99 jobs confirmed)
UPDATE career_endpoint
SET ats_type = 'ASHBY', ats_slug = 'plaid',
    url = 'https://jobs.ashbyhq.com/plaid',
    extraction_method = 'STRUCTURED_API', confidence = 'HIGH',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE company_id = 'a0000043-0000-0000-0000-000000000043';

-- Segment: GREENHOUSE -> deactivate (acquired by Twilio, no separate board)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company acquired by Twilio; no separate job board'
WHERE company_id = 'a0000046-0000-0000-0000-000000000046';

-- Aiven: GREENHOUSE -> deactivate (no supported ATS found)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated away from Greenhouse; no supported ATS found'
WHERE company_id = 'a0000049-0000-0000-0000-000000000049';

-- Personio (company): GREENHOUSE -> deactivate (board closed, Personio API blocked)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company closed Greenhouse board; no supported ATS found'
WHERE company_id = 'a0000001-0000-0000-0000-000000000001';

-- ============================================================================
-- Fix discovered endpoints (match by url/ats_slug since no fixed company_id)
-- ============================================================================

-- King: WORKABLE (wrong - KING ICT is a different company; King gaming uses PhenomPeople)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Workable slug matches wrong company (KING ICT); King gaming uses unsupported PhenomPeople'
WHERE ats_type = 'WORKABLE' AND ats_slug = 'king';

-- Hetzner: WORKABLE (wrong company or 0 jobs; actual career site is custom at career.hetzner.com)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Hetzner uses custom career platform at career.hetzner.com; not Workable'
WHERE ats_type = 'WORKABLE' AND ats_slug = 'hetzner';

-- Bolt: WORKABLE (different Bolt company or 0 jobs; Bolt ridesharing uses custom platform)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Bolt ridesharing uses custom platform at bolt.eu/en/careers; not Workable'
WHERE ats_type = 'WORKABLE' AND ats_slug = 'bolt';

-- sennder: PERSONIO (Personio API blocked by Vercel bot protection, returns 307->429)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Personio API blocked by bot protection (307->429); no alternative ATS found'
WHERE ats_type = 'PERSONIO' AND ats_slug = 'sennder';

-- MOIA: GREENHOUSE (API works fine - 53 jobs confirmed; reset transient error)
UPDATE career_endpoint
SET last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE ats_type = 'GREENHOUSE' AND ats_slug = 'moia';

-- Miro duplicate with slug 'realtimeboardglobal' (errored): reset error
-- The main miro endpoint (company_id a0000022) was already updated above
UPDATE career_endpoint
SET last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE ats_type = 'GREENHOUSE' AND ats_slug = 'realtimeboardglobal'
  AND company_id != 'a0000022-0000-0000-0000-000000000022';

-- TeamViewer: SMARTRECRUITERS -> deactivate (now uses Teamtailor, no extractor available)
UPDATE career_endpoint
SET is_active = false, last_error_message = 'Company migrated to Teamtailor; no extractor available'
WHERE ats_type = 'SMARTRECRUITERS' AND ats_slug = 'TeamViewer';

-- HubSpot: GREENHOUSE slug 'hubspot' -> 'hubspotjobs' (177 jobs confirmed)
UPDATE career_endpoint
SET ats_slug = 'hubspotjobs',
    last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE ats_type = 'GREENHOUSE' AND ats_slug = 'hubspot';

-- Ottobock: SUCCESSFACTORS (API works, 9 jobs; reset transient error)
UPDATE career_endpoint
SET last_crawl_status = NULL, last_error_message = NULL, consecutive_errors = 0
WHERE ats_type = 'SUCCESSFACTORS' AND url LIKE '%ottobock%';
