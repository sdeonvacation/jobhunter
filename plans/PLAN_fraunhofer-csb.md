# Plan: Fraunhofer CSB Job Board Integration

## Overview

Integrate the Fraunhofer job board (`jobs.fraunhofer.de`, SuccessFactors Career Site Builder) by reusing the existing `SuccessFactorsStrategy` non-classic HTML path, which live inspection confirmed is already compatible (search pagination, listing table, and `.jobdescription` selectors all match). Because Fraunhofer is a research org (~839 jobs, mostly non-SWE), the crawl is pre-filtered board-side with keyword search `q=Software` (162 jobs, verified to contain both user reference jobs), with the existing pipeline RoleFilter as the precise gate. Work: endpoint seeding, query-param passthrough in the strategy, fixture tests, and two small data-quality enhancements (postedDate + streetAddress from detail-page microdata).

## Tech Stack

- Java 21, Spring Boot 3.3.5 (existing api/ module)
- Jsoup for HTML parsing (existing)
- Liquibase for endpoint seeding
- JUnit 5 (unit, fixture-based; no new Testcontainers needed)

## Testing Strategy

- Unit: extend `SuccessFactorsStrategyTest` with Fraunhofer-shaped fixtures (real captured HTML, sanitized): pagination label `Results <b>1 – 25</b> of <b>839</b>`, `tr.data-row` listings, `span.jobdescription` description, query-param passthrough, microdata `datePosted`/`streetAddress` parsing.
- Integration: manual — `POST /api/admin/crawl/{endpointId}` against live site after seeding; verify job count ≈ 162 incl. reference reqIds 1372103633 and 887698201.
- Done when: unit tests green without network; live crawl of the new endpoint returns jobs into the normal pipeline (score/filters/digest unchanged).

## Phases

### Phase 1: Query-param passthrough in strategy

- Step 1: Refactor `fetchSearchPage` to build pagination URLs from the endpoint URL's own query params (`q`, `locationsearch`, `locale`) when present, falling back to current hardcoded defaults for endpoints without params.
- Step 2: Unit tests: endpoint URL with `q=Software` paginates with `q=Software`; endpoint URL without params keeps `q=&locationsearch=Germany&locale=en_US` (no behavior change for existing endpoints).

### Phase 2: Seed Fraunhofer endpoint

- Step 1: Add Liquibase changelog inserting Company `Fraunhofer-Gesellschaft` and `CareerEndpoint(url=https://jobs.fraunhofer.de/search/?q=Software&locale=en_US, atsType=SUCCESSFACTORS, isActive=true)`, following `002-seed-companies.sql` conventions.
- Step 2: Set `crawlFrequencyHours` deliberately (suggest 24; ~162 detail fetches per run at ~150 ms delay).

### Phase 3: Fixture tests for Fraunhofer page shape

- Step 1: Capture and sanitize fixtures: one `q=Software` search page (with pagination label), one detail page.
- Step 2: Assert `parseTotalCount` = 162 on the fixture (en-dash + `<b>` variant).
- Step 3: Assert `parseListings` yields 25 listings with numeric externalId, city from col 2, absolute URL.
- Step 4: Assert `parseDescription` extracts text from `span.jobdescription`.

### Phase 4: Microdata enhancements (small, contained)

- Step 1: In detail-page parsing, extract `meta[itemprop=datePosted]` (format `EEE MMM dd HH:mm:ss zzz yyyy`, Locale.ENGLISH) → `RawAggregatorJob.postedDate`.
- Step 2: Extract `meta[itemprop=streetAddress]` (e.g. "Aachen, DE, 52074") as location fallback when table cell is blank.
- Step 3: Unit tests for both.

### Phase 5: Live verification

- Step 1: Rebuild, run migration, trigger `POST /api/admin/crawl/{endpointId}`.
- Step 2: Verify jobs appear in `/api/jobs` pipeline with descriptions; confirm both reference reqIds present; check health report for the endpoint.

## Risks/Edge cases

- Keyword `q=Software` is full-text (title + description): some non-SWE research roles mentioning "software" in descriptions will pass the board filter — existing RoleFilter include-patterns (title-based) are the precise gate; both reference jobs pass via `software`.
- Keyword recall: future relevant roles whose titles/descriptions lack "software" (e.g. German-only "Sichere Software" contains it; pure "Programmiersprachen" might not) — acceptable tradeoff vs crawling 839; `q` is editable in the endpoint row without code change.
- `locationsearch=Germany` hardcoded today: replaced by endpoint-param passthrough (Phase 1), which also removes this latent debt for non-German CSB sites.
- `datePosted` is Java `Date.toString` format: parse with strict `DateTimeFormatter` pattern + Locale.ENGLISH; on failure, leave `postedDate` null (never fail the job).
- German-only titles: existing language filter (`Lingua`) handles; no change.
- Sitemap `lastmod` uniform: do not use for change detection (search pagination covers sync).
