# Plan: Instaffo Integration

## Overview

Integrate Instaffo (Germany's leading tech/marketing/sales job platform) as a new aggregator source. Uses sitemap-based job URL discovery + Jsoup HTML scraping of individual job detail pages. ~301 Software Engineering jobs available, with transparent salary data and Germany-focused positions.

## Tech Stack

- Java 21, Spring Boot 3.3.5
- Jsoup (already in classpath) for HTML parsing
- Java SAX/DOM for XML sitemap parsing
- Existing `FetchStrategy` interface + `AggregatorIngestionService` pipeline

## Testing Strategy

- Unit: Mock HTTP responses, test sitemap XML parsing, test job detail HTML extraction, test field mapping
- Integration: WireMock-based test hitting fake sitemap + detail pages, verifying end-to-end RawAggregatorJob construction
- Done when: `./gradlew test --tests "dev.jobhunter.strategy.aggregator.InstaffoStrategyTest"` passes; manual crawl via `POST /api/admin/crawl-source/instaffo` returns >0 jobs

## Phases

### Phase 1: Enum & Configuration

- Step 1: Add `INSTAFFO` to `JobSource` enum and `aggregators()` list
- Step 2: Add `INSTAFFO` to `DiscoverySource` enum
- Step 3: Add YAML config under `aggregator.sources` in `application.yaml`

### Phase 2: InstaffoStrategy Implementation

- Step 1: Create `InstaffoStrategy` class implementing `FetchStrategy`
- Step 2: Implement sitemap fetching — GET `https://jobs.instaffo.com/sitemap-jobs.xml`, parse `<loc>` URLs
- Step 3: Filter URLs to only `/en/job/` paths (skip category pages, static pages)
- Step 4: Implement job detail page scraping — Jsoup GET each URL, extract: title, company, location, salary, description, posted date
- Step 5: Map extracted data to `RawAggregatorJob` records
- Step 6: Add rate limiting (delay between detail page fetches) to avoid 429s

### Phase 3: Registration & Wiring

- Step 1: Register strategy via `@Component` + `supports()` method or via YAML config
- Step 2: Verify `PipelineScheduler` picks up new source automatically via `SourceConfigAggregator`
- Step 3: Add `POST /api/admin/crawl-source/instaffo` endpoint for manual trigger (if not already generic)

### Phase 4: Testing & Validation

- Step 1: Write unit tests with sample sitemap XML and job page HTML fixtures
- Step 2: Run manual crawl, verify jobs created with correct fields
- Step 3: Verify filters (language, role, location, YOE) run correctly on ingested jobs
- Step 4: Confirm scoring pipeline runs after ingestion

## Risks/Edge cases

- **Rate limiting (429)**: Instaffo may throttle high-volume scraping. Mitigation: add 300-500ms delay between detail page fetches, respect `Retry-After` headers, cap at 50 pages per run.
- **HTML structure changes**: Next.js SSR markup can change on deploys. Mitigation: use semantic selectors (data-testid, schema.org JSON-LD) over CSS class names; add monitoring for parse failures.
- **Sitemap staleness**: Sitemap may include expired/removed jobs. Mitigation: handle 404/410 gracefully, skip without incrementing errors.
- **Duplicate jobs**: Same job posted on Instaffo AND company's own ATS (already tracked). Mitigation: existing fingerprint deduplication (title+company+location) in `AggregatorIngestionServiceImpl` handles this.
- **Large sitemap**: If sitemap contains 2000+ URLs but we only want Software Engineering. Mitigation: filter by URL pattern or only scrape first N pages per category; or use category page `/en/jobs/software-entwicklung` as discovery instead of full sitemap.
- **German-only descriptions**: Many Instaffo jobs are in German. Mitigation: existing `LanguageFilter` will mark them SKIP — no special handling needed.
- **Job URL as externalId**: Use the UUID segment from URL (`/en/job/{slug}-{uuid}`) as externalId for stable dedup even if title/slug changes.
