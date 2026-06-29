--liquibase formatted sql

--changeset jobhunter:016-drop-extraction-method
-- Migrate 4 legacy rows that predate AtsType dispatch, then drop the dead column.
-- ExtractionMethod was the original extractor selector; AtsType replaced it entirely.
-- CrawlService uses strategyRegistry.getStrategy(atsType) — extraction_method is never read.
UPDATE career_endpoint SET extraction_method = 'CUSTOM' WHERE extraction_method IN ('GREENHOUSE','PERSONIO','RECRUITEE','SCRAPE');
ALTER TABLE career_endpoint DROP COLUMN extraction_method;
