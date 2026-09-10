--liquibase formatted sql
--changeset jobhunter:020-seed-fraunhofer

-- Fraunhofer-Gesellschaft (SuccessFactors CSB board with q=Software pre-filter)
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000052-0000-0000-0000-000000000052', 'Fraunhofer-Gesellschaft', 'fraunhofer',
        'fraunhofer.de', 'Germany', true, 'ACTIVE', 'MANUAL', 65);

INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, confidence, verified, is_active, crawl_frequency_hours, source)
VALUES ('a0000052-0000-0000-0000-000000000052',
        'https://jobs.fraunhofer.de/search/?q=Software&locale=en_US',
        'SUCCESSFACTORS', 'fraunhofer', 'HIGH', true, true, 24, 'MANUAL');