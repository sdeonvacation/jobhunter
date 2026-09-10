# Requirement: Fraunhofer Job Board Integration

## Source

User request: inspect how https://jobs.fraunhofer.de/search/?locale=en_US can be scraped, then plan extractor integration.

## Problem

Fraunhofer-Gesellschaft (76 institutes, ~839 open positions) is not currently crawled. Its job board is a SuccessFactors **Career Site Builder (CSB)** deployment on a custom domain (`jobs.fraunhofer.de`), which the existing `SuccessFactorsStrategy` classic-board path (XML API on `careerN.successfactors.eu`) does not cover.

## Verified Facts (live inspection, 2026-09-08)

- Site is fully server-rendered; no JS shell, no JSON API needed.
- Search: `/search/?q=&sortColumn=referencedate&sortDirection=desc&startrow=N`, 25/page, 839 jobs / 34 pages. Adding `locationsearch=Germany` (what the current strategy sends) does not reduce results (still 839).
- Pagination label: `Results <b>1 – 25</b> of <b>839</b>` (en-dash, `<b>` tags) — matches existing `TOTAL_COUNT_PATTERN` after `<b>` strip.
- Listings: `<tr class="data-row">` with `/job/{City}-{Title}-{PostalCode}/{reqId}/` links; col 2 = city, col 3 = institute. Matches existing `parseListings`.
- Detail pages: schema.org **microdata** (`itemprop="datePosted"` in Java `Date.toString` format, `itemprop="streetAddress"`) and description in `span.jobdescription` — first selector of existing `parseDescription` matches.
- `sitemap.xml` lists all 839 URLs but `lastmod` is uniform (build date) → not useful for change detection.
- Classic SF XML API returns HTML fallback on this domain (not usable). No RSS.
- robots.txt allows `/search/` and `/job/`; disallows `/services/`, `/preapply/`, `/talentcommunity/` (not needed).
- Stable external ID: numeric reqId in URL path.

## Target Job Profile

User seeks software-security research roles, e.g.:
- `Research Associate for Secure Software Development in Heilbronn` (reqId 1372103633)
- `Research Assistant Software Security & Program Analysis (anywhere in Germany)` (reqId 887698201)

Both verified present in board-side keyword search `q=Software` (162 of 839 results). Existing `profile.yaml` role include-pattern `software` passes both titles, so no filter config change needed.

## Requirements

1. R1: Jobs from `jobs.fraunhofer.de` must be crawled, stored, scored, and surfaced like other ATS jobs.
2. R2: Reuse the existing `SuccessFactorsStrategy` CSB (non-classic) code path; no new strategy class.
3. R3: Board-side pre-filter: seed the endpoint URL with `q=Software` (839 → 162 jobs) so irrelevant research roles (PhD, student assistants, non-SWE science) are never fetched.
4. R4: Strategy must carry the endpoint URL's query params (`q`, `locationsearch`, `locale`) into pagination URLs instead of hardcoding `q=&locationsearch=Germany&locale=en_US`.
5. R5: Seed Company + CareerEndpoint (`atsType=SUCCESSFACTORS`) via Liquibase, following `002-seed-companies.sql` pattern.
6. R6: Add fixture-based unit tests locking in the Fraunhofer page shapes (pagination label variant, data-row table, `.jobdescription` description, query-param passthrough).
7. R7 (enhancement): populate `RawAggregatorJob.postedDate` from detail-page microdata `datePosted`; use microdata `streetAddress` as location fallback when table cell is blank.

## Out of Scope

- Generic hardening of `locationsearch=Germany` for non-German CSB sites (verified harmless here; noted as future work).
- Sitemap-based sync (redundant with search pagination).
- AI extraction fallback (site is server-rendered; structured parsing suffices).

## Acceptance Criteria

- `POST /api/admin/crawl/{endpointId}` on the Fraunhofer endpoint yields ~162 jobs (q=Software subset), including both reference reqIds 1372103633 and 887698201, with title, city, description, apply URL.
- Unit tests pass without network access (fixtures captured from live pages).
- No changes to other endpoints' crawl behavior (endpoints without `q` in URL keep current defaults).
