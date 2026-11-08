-- Stop crawling university/research endpoints.
-- career_endpoint -> company join; match academic tokens in the company name.
-- Run the SELECT first, review the rows, then uncomment and run the UPDATE.
--
-- DB: jobhunter @ localhost:5435, user jobhunter
--   psql -h localhost -p 5435 -U jobhunter -d jobhunter -f scripts/disable-academic-endpoints.sql

-- 1. Preview academic/research endpoints that are currently active.
SELECT ce.id,
       c.name                AS company,
       ce.url,
       ce.ats_type,
       ce.is_active,
       ce.last_crawl_status
FROM career_endpoint ce
JOIN company c ON ce.company_id = c.id
WHERE ce.is_active = true
  AND c.name ~* 'universit|hochschule|fachhochschule|fraunhofer|max[- ]?planck|helmholtz|leibniz|\brwth\b|institut|\bacadem|\bcollege\b'
ORDER BY c.name;

-- 2. Deactivate them (review the SELECT output before uncommenting).
-- UPDATE career_endpoint ce
-- SET is_active = false
-- FROM company c
-- WHERE ce.company_id = c.id
--   AND ce.is_active = true
--   AND c.name ~* 'universit|hochschule|fachhochschule|fraunhofer|max[- ]?planck|helmholtz|leibniz|\brwth\b|institut|\bacadem|\bcollege\b';
