--liquibase formatted sql
--changeset jobhub:002-seed-companies

-- Personio
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000001-0000-0000-0000-000000000001', 'Personio', 'personio', 'personio.com', 'Germany', true, 'ACTIVE', 'MANUAL', 75);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000001-0000-0000-0000-000000000001', 'https://boards.greenhouse.io/personio', 'GREENHOUSE', 'personio', 'STRUCTURED_API', 'HIGH', true, true);

-- Celonis
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000002-0000-0000-0000-000000000002', 'Celonis', 'celonis', 'celonis.com', 'Germany', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000002-0000-0000-0000-000000000002', 'https://jobs.lever.co/celonis', 'LEVER', 'celonis', 'STRUCTURED_API', 'HIGH', true, true);

-- SAP
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000003-0000-0000-0000-000000000003', 'SAP', 'sap', 'sap.com', 'Germany', true, 'ACTIVE', 'MANUAL', 65);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000003-0000-0000-0000-000000000003', 'https://jobs.sap.com', 'WORKDAY', 'SAP', 'CUSTOM', 'MEDIUM', true, true);

-- Delivery Hero
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000004-0000-0000-0000-000000000004', 'Delivery Hero', 'delivery-hero', 'deliveryhero.com', 'Germany', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000004-0000-0000-0000-000000000004', 'https://boards.greenhouse.io/deliveryhero', 'GREENHOUSE', 'deliveryhero', 'STRUCTURED_API', 'HIGH', true, true);

-- N26
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000005-0000-0000-0000-000000000005', 'N26', 'n26', 'n26.com', 'Germany', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000005-0000-0000-0000-000000000005', 'https://boards.greenhouse.io/n26', 'GREENHOUSE', 'n26', 'STRUCTURED_API', 'HIGH', true, true);

-- Trade Republic
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000006-0000-0000-0000-000000000006', 'Trade Republic', 'trade-republic', 'traderepublic.com', 'Germany', true, 'ACTIVE', 'MANUAL', 74);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000006-0000-0000-0000-000000000006', 'https://boards.greenhouse.io/traderepublic', 'GREENHOUSE', 'traderepublic', 'STRUCTURED_API', 'HIGH', true, true);

-- Scalable Capital
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000007-0000-0000-0000-000000000007', 'Scalable Capital', 'scalable-capital', 'scalable.capital', 'Germany', true, 'ACTIVE', 'MANUAL', 68);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000007-0000-0000-0000-000000000007', 'https://jobs.lever.co/scalablecapital', 'LEVER', 'scalablecapital', 'STRUCTURED_API', 'HIGH', true, true);

-- Contentful
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000008-0000-0000-0000-000000000008', 'Contentful', 'contentful', 'contentful.com', 'Germany', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000008-0000-0000-0000-000000000008', 'https://boards.greenhouse.io/contentful', 'GREENHOUSE', 'contentful', 'STRUCTURED_API', 'HIGH', true, true);

-- JetBrains
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000009-0000-0000-0000-000000000009', 'JetBrains', 'jetbrains', 'jetbrains.com', 'Netherlands', true, 'ACTIVE', 'MANUAL', 80);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000009-0000-0000-0000-000000000009', 'https://jobs.lever.co/jetbrains', 'LEVER', 'jetbrains', 'STRUCTURED_API', 'HIGH', true, true);

-- Zalando
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000010-0000-0000-0000-000000000010', 'Zalando', 'zalando', 'zalando.com', 'Germany', true, 'ACTIVE', 'MANUAL', 68);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000010-0000-0000-0000-000000000010', 'https://jobs.zalando.com', 'WORKDAY', 'zalando', 'CUSTOM', 'MEDIUM', true, true);

-- Auto1
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000011-0000-0000-0000-000000000011', 'Auto1 Group', 'auto1-group', 'auto1-group.com', 'Germany', true, 'ACTIVE', 'MANUAL', 60);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000011-0000-0000-0000-000000000011', 'https://boards.greenhouse.io/auto1group', 'GREENHOUSE', 'auto1group', 'STRUCTURED_API', 'HIGH', true, true);

-- FlixBus (Flix)
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000012-0000-0000-0000-000000000012', 'Flix', 'flix', 'flixbus.com', 'Germany', true, 'ACTIVE', 'MANUAL', 65);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000012-0000-0000-0000-000000000012', 'https://jobs.lever.co/flixbus', 'LEVER', 'flixbus', 'STRUCTURED_API', 'HIGH', true, true);

-- SumUp
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000013-0000-0000-0000-000000000013', 'SumUp', 'sumup', 'sumup.com', 'Germany', true, 'ACTIVE', 'MANUAL', 66);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000013-0000-0000-0000-000000000013', 'https://boards.greenhouse.io/sumup', 'GREENHOUSE', 'sumup', 'STRUCTURED_API', 'HIGH', true, true);

-- Wefox
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000014-0000-0000-0000-000000000014', 'Wefox', 'wefox', 'wefox.com', 'Germany', true, 'ACTIVE', 'MANUAL', 55);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000014-0000-0000-0000-000000000014', 'https://boards.greenhouse.io/wefox', 'GREENHOUSE', 'wefox', 'STRUCTURED_API', 'MEDIUM', true, true);

-- Mambu
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000015-0000-0000-0000-000000000015', 'Mambu', 'mambu', 'mambu.com', 'Germany', true, 'ACTIVE', 'MANUAL', 64);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000015-0000-0000-0000-000000000015', 'https://boards.greenhouse.io/mambu', 'GREENHOUSE', 'mambu', 'STRUCTURED_API', 'HIGH', true, true);

-- Mollie
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000016-0000-0000-0000-000000000016', 'Mollie', 'mollie', 'mollie.com', 'Netherlands', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000016-0000-0000-0000-000000000016', 'https://boards.greenhouse.io/mollie', 'GREENHOUSE', 'mollie', 'STRUCTURED_API', 'HIGH', true, true);

-- Adyen
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000017-0000-0000-0000-000000000017', 'Adyen', 'adyen', 'adyen.com', 'Netherlands', true, 'ACTIVE', 'MANUAL', 78);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000017-0000-0000-0000-000000000017', 'https://careers.adyen.com', 'CUSTOM', 'adyen', 'CUSTOM', 'MEDIUM', true, true);

-- Booking.com
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000018-0000-0000-0000-000000000018', 'Booking.com', 'booking-com', 'booking.com', 'Netherlands', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000018-0000-0000-0000-000000000018', 'https://jobs.booking.com', 'WORKDAY', 'booking', 'CUSTOM', 'MEDIUM', true, true);

-- Klarna
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000019-0000-0000-0000-000000000019', 'Klarna', 'klarna', 'klarna.com', 'Germany', true, 'ACTIVE', 'MANUAL', 68);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000019-0000-0000-0000-000000000019', 'https://boards.greenhouse.io/klarna', 'GREENHOUSE', 'klarna', 'STRUCTURED_API', 'HIGH', true, true);

-- Spotify
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000020-0000-0000-0000-000000000020', 'Spotify', 'spotify', 'spotify.com', 'Sweden', true, 'ACTIVE', 'MANUAL', 82);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000020-0000-0000-0000-000000000020', 'https://jobs.lever.co/spotify', 'LEVER', 'spotify', 'STRUCTURED_API', 'HIGH', true, true);

-- King
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000021-0000-0000-0000-000000000021', 'King', 'king', 'king.com', 'Sweden', true, 'ACTIVE', 'MANUAL', 55);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000021-0000-0000-0000-000000000021', 'https://careers.king.com', 'CUSTOM', 'king', 'CUSTOM', 'MEDIUM', true, true);

-- Miro
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000022-0000-0000-0000-000000000022', 'Miro', 'miro', 'miro.com', 'Netherlands', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000022-0000-0000-0000-000000000022', 'https://boards.greenhouse.io/miro', 'GREENHOUSE', 'miro', 'STRUCTURED_API', 'HIGH', true, true);

-- GitLab
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000023-0000-0000-0000-000000000023', 'GitLab', 'gitlab', 'gitlab.com', 'Remote', true, 'ACTIVE', 'MANUAL', 80);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000023-0000-0000-0000-000000000023', 'https://boards.greenhouse.io/gitlab', 'GREENHOUSE', 'gitlab', 'STRUCTURED_API', 'HIGH', true, true);

-- Datadog
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000024-0000-0000-0000-000000000024', 'Datadog', 'datadog', 'datadoghq.com', 'Remote', true, 'ACTIVE', 'MANUAL', 78);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000024-0000-0000-0000-000000000024', 'https://boards.greenhouse.io/datadog', 'GREENHOUSE', 'datadog', 'STRUCTURED_API', 'HIGH', true, true);

-- MongoDB
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000025-0000-0000-0000-000000000025', 'MongoDB', 'mongodb', 'mongodb.com', 'Remote', true, 'ACTIVE', 'MANUAL', 74);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000025-0000-0000-0000-000000000025', 'https://boards.greenhouse.io/mongodb', 'GREENHOUSE', 'mongodb', 'STRUCTURED_API', 'HIGH', true, true);

-- Elastic
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000026-0000-0000-0000-000000000026', 'Elastic', 'elastic', 'elastic.co', 'Remote', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000026-0000-0000-0000-000000000026', 'https://jobs.elastic.co', 'CUSTOM', 'elastic', 'CUSTOM', 'MEDIUM', true, true);

-- Confluent
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000027-0000-0000-0000-000000000027', 'Confluent', 'confluent', 'confluent.io', 'Remote', true, 'ACTIVE', 'MANUAL', 76);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000027-0000-0000-0000-000000000027', 'https://boards.greenhouse.io/confluent', 'GREENHOUSE', 'confluent', 'STRUCTURED_API', 'HIGH', true, true);

-- HashiCorp
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000028-0000-0000-0000-000000000028', 'HashiCorp', 'hashicorp', 'hashicorp.com', 'Remote', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000028-0000-0000-0000-000000000028', 'https://boards.greenhouse.io/hashicorp', 'GREENHOUSE', 'hashicorp', 'STRUCTURED_API', 'HIGH', true, true);

-- Grafana Labs
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000029-0000-0000-0000-000000000029', 'Grafana Labs', 'grafana-labs', 'grafana.com', 'Remote', true, 'ACTIVE', 'MANUAL', 78);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000029-0000-0000-0000-000000000029', 'https://boards.greenhouse.io/grafanalabs', 'GREENHOUSE', 'grafanalabs', 'STRUCTURED_API', 'HIGH', true, true);

-- Snyk
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000030-0000-0000-0000-000000000030', 'Snyk', 'snyk', 'snyk.io', 'Remote', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000030-0000-0000-0000-000000000030', 'https://boards.greenhouse.io/snyk', 'GREENHOUSE', 'snyk', 'STRUCTURED_API', 'HIGH', true, true);

-- CircleCI
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000031-0000-0000-0000-000000000031', 'CircleCI', 'circleci', 'circleci.com', 'Remote', true, 'ACTIVE', 'MANUAL', 62);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000031-0000-0000-0000-000000000031', 'https://boards.greenhouse.io/circleci', 'GREENHOUSE', 'circleci', 'STRUCTURED_API', 'HIGH', true, true);

-- Sentry
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000032-0000-0000-0000-000000000032', 'Sentry', 'sentry', 'sentry.io', 'Remote', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000032-0000-0000-0000-000000000032', 'https://boards.greenhouse.io/sentry', 'GREENHOUSE', 'sentry', 'STRUCTURED_API', 'HIGH', true, true);

-- PlanetScale
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000033-0000-0000-0000-000000000033', 'PlanetScale', 'planetscale', 'planetscale.com', 'Remote', true, 'ACTIVE', 'MANUAL', 66);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000033-0000-0000-0000-000000000033', 'https://boards.greenhouse.io/planetscale', 'GREENHOUSE', 'planetscale', 'STRUCTURED_API', 'HIGH', true, true);

-- Vercel
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000034-0000-0000-0000-000000000034', 'Vercel', 'vercel', 'vercel.com', 'Remote', true, 'ACTIVE', 'MANUAL', 68);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000034-0000-0000-0000-000000000034', 'https://boards.greenhouse.io/vercel', 'GREENHOUSE', 'vercel', 'STRUCTURED_API', 'HIGH', true, true);

-- Supabase
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000035-0000-0000-0000-000000000035', 'Supabase', 'supabase', 'supabase.com', 'Remote', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000035-0000-0000-0000-000000000035', 'https://boards.greenhouse.io/supabase', 'GREENHOUSE', 'supabase', 'STRUCTURED_API', 'HIGH', true, true);

-- Cloudflare
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000036-0000-0000-0000-000000000036', 'Cloudflare', 'cloudflare', 'cloudflare.com', 'Remote', true, 'ACTIVE', 'MANUAL', 80);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000036-0000-0000-0000-000000000036', 'https://boards.greenhouse.io/cloudflare', 'GREENHOUSE', 'cloudflare', 'STRUCTURED_API', 'HIGH', true, true);

-- Wise
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000037-0000-0000-0000-000000000037', 'Wise', 'wise', 'wise.com', 'UK', true, 'ACTIVE', 'MANUAL', 76);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000037-0000-0000-0000-000000000037', 'https://boards.greenhouse.io/wise', 'GREENHOUSE', 'wise', 'STRUCTURED_API', 'HIGH', true, true);

-- Revolut
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000038-0000-0000-0000-000000000038', 'Revolut', 'revolut', 'revolut.com', 'UK', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000038-0000-0000-0000-000000000038', 'https://jobs.lever.co/revolut', 'LEVER', 'revolut', 'STRUCTURED_API', 'HIGH', true, true);

-- Monzo
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000039-0000-0000-0000-000000000039', 'Monzo', 'monzo', 'monzo.com', 'UK', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000039-0000-0000-0000-000000000039', 'https://boards.greenhouse.io/monzo', 'GREENHOUSE', 'monzo', 'STRUCTURED_API', 'HIGH', true, true);

-- GoCardless
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000040-0000-0000-0000-000000000040', 'GoCardless', 'gocardless', 'gocardless.com', 'UK', true, 'ACTIVE', 'MANUAL', 68);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000040-0000-0000-0000-000000000040', 'https://boards.greenhouse.io/gocardless', 'GREENHOUSE', 'gocardless', 'STRUCTURED_API', 'HIGH', true, true);

-- Thought Machine
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000041-0000-0000-0000-000000000041', 'Thought Machine', 'thought-machine', 'thoughtmachine.net', 'UK', true, 'ACTIVE', 'MANUAL', 72);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000041-0000-0000-0000-000000000041', 'https://boards.greenhouse.io/thoughtmachine', 'GREENHOUSE', 'thoughtmachine', 'STRUCTURED_API', 'HIGH', true, true);

-- Form3
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000042-0000-0000-0000-000000000042', 'Form3', 'form3', 'form3.tech', 'UK', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000042-0000-0000-0000-000000000042', 'https://boards.greenhouse.io/form3', 'GREENHOUSE', 'form3', 'STRUCTURED_API', 'HIGH', true, true);

-- Plaid
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000043-0000-0000-0000-000000000043', 'Plaid', 'plaid', 'plaid.com', 'Remote', true, 'ACTIVE', 'MANUAL', 74);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000043-0000-0000-0000-000000000043', 'https://boards.greenhouse.io/plaid', 'GREENHOUSE', 'plaid', 'STRUCTURED_API', 'HIGH', true, true);

-- Stripe
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000044-0000-0000-0000-000000000044', 'Stripe', 'stripe', 'stripe.com', 'Remote', true, 'ACTIVE', 'MANUAL', 85);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000044-0000-0000-0000-000000000044', 'https://stripe.com/jobs', 'CUSTOM', 'stripe', 'CUSTOM', 'MEDIUM', true, true);

-- Twilio
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000045-0000-0000-0000-000000000045', 'Twilio', 'twilio', 'twilio.com', 'Remote', true, 'ACTIVE', 'MANUAL', 70);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000045-0000-0000-0000-000000000045', 'https://boards.greenhouse.io/twilio', 'GREENHOUSE', 'twilio', 'STRUCTURED_API', 'HIGH', true, true);

-- Segment
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000046-0000-0000-0000-000000000046', 'Segment', 'segment', 'segment.com', 'Remote', true, 'ACTIVE', 'MANUAL', 66);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000046-0000-0000-0000-000000000046', 'https://boards.greenhouse.io/segment', 'GREENHOUSE', 'segment', 'STRUCTURED_API', 'HIGH', true, true);

-- LaunchDarkly
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000047-0000-0000-0000-000000000047', 'LaunchDarkly', 'launchdarkly', 'launchdarkly.com', 'Remote', true, 'ACTIVE', 'MANUAL', 66);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000047-0000-0000-0000-000000000047', 'https://boards.greenhouse.io/launchdarkly', 'GREENHOUSE', 'launchdarkly', 'STRUCTURED_API', 'HIGH', true, true);

-- Postman
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000048-0000-0000-0000-000000000048', 'Postman', 'postman', 'postman.com', 'Remote', true, 'ACTIVE', 'MANUAL', 64);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000048-0000-0000-0000-000000000048', 'https://boards.greenhouse.io/postman', 'GREENHOUSE', 'postman', 'STRUCTURED_API', 'HIGH', true, true);

-- Aiven
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000049-0000-0000-0000-000000000049', 'Aiven', 'aiven', 'aiven.io', 'Remote', true, 'ACTIVE', 'MANUAL', 68);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000049-0000-0000-0000-000000000049', 'https://boards.greenhouse.io/aiven', 'GREENHOUSE', 'aiven', 'STRUCTURED_API', 'HIGH', true, true);

-- Hetzner
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000050-0000-0000-0000-000000000050', 'Hetzner', 'hetzner', 'hetzner.com', 'Germany', true, 'ACTIVE', 'MANUAL', 58);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000050-0000-0000-0000-000000000050', 'https://www.hetzner.com/unternehmen/karriere', 'CUSTOM', 'hetzner', 'CUSTOM', 'LOW', true, true);

-- IONOS
INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000051-0000-0000-0000-000000000051', 'IONOS', 'ionos', 'ionos.com', 'Germany', true, 'ACTIVE', 'MANUAL', 56);
INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, extraction_method, confidence, verified, is_active)
VALUES ('a0000051-0000-0000-0000-000000000051', 'https://jobs.ionos.com', 'CUSTOM', 'ionos', 'CUSTOM', 'LOW', true, true);
