# HLD: Fraunhofer CSB Job Board Integration

## Tech Stack

| Category  | Technology | Purpose |
| --------- | ---------- | ------- |
| Language  | Java 21 (Temurin) | Existing `api/` module; no new runtime |
| Framework | Spring Boot 3.3.5, WebClient (Reactor) | Injected HTTP client already used by `SuccessFactorsStrategy` |
| Parsing   | Jsoup 1.18.1 | HTML + microdata (`meta[itemprop=*]`) extraction |
| Database  | PostgreSQL 16 + Liquibase | Seed Company + CareerEndpoint via SQL changelog |
| Testing   | JUnit 5 + AssertJ + Mockito | Fixture-based unit tests (no network, no Testcontainers) |

No new dependencies, no new framework components. The work is confined to one existing strategy class, one Liquibase changelog, and one test class.

## Components

| Component | Responsibility | Dependencies |
| --------- | -------------- | ------------ |
| `CrawlService` (`service/CrawlService.java`) | Orchestrates endpoint crawl; dispatches via `StrategyRegistry.getStrategy(AtsType)`; persists `RawAggregatorJob` → `JobPosting`; sets `source = JobSource.fromAtsType(endpoint.getAtsType())` (line 327) | `StrategyRegistry`, `CareerEndpointRepository`, `JobPostingRepository` |
| `StrategyRegistry` (`ingestion/StrategyRegistry.java`) | Maps `AtsType.SUCCESSFACTORS` → `SuccessFactorsStrategy` | `List<FetchStrategy>` |
| `SuccessFactorsStrategy` (`strategy/ats/SuccessFactorsStrategy.java`) | CSB HTML crawl: search pagination, listing table parse, detail-page description + microdata parse. **Modified in this work** | `WebClient`, `AbstractAtsStrategy` |
| `AbstractAtsStrategy` | Shared helpers: `truncate`, `stripHtml`, `parseIsoDate`, `elapsed`, `safeExecute` | — |
| `FetchContext` / `FetchResult` / `RawAggregatorJob` (`strategy/`) | Value records threading endpoint + job payload through the pipeline | `CareerEndpoint`, `ExtractionStatus` |
| Liquibase changelog `020-seed-fraunhofer.sql` | Seeds `Company` + `CareerEndpoint` rows | `master.xml`, `company`, `career_endpoint` |
| Downstream pipeline (unchanged) | RoleFilter, Lingua language filter, dedup, scoring, digest | `profile.yaml` |

## Architecture

```
                         ┌────────────────────────────────────────────┐
                         │  Liquibase changelog (020-seed-fraunhofer)  │
                         │   company: Fraunhofer-Gesellschaft          │
                         │   career_endpoint: url=?q=Software...,       │
                         │     ats_type=SUCCESSFACTORS, crawl_freq=24h  │
                         └───────────────────┬────────────────────────┘
                                             │ migrates on startup
                                             ▼
 ┌──────────────┐   POST /api/admin/crawl/{id}   ┌──────────────────────┐
 │ Admin        │ ─────────────────────────────▶ │ CrawlService         │
 │ Controller   │                                │  strategyRegistry.   │
 └──────────────┘                                │   getStrategy(       │
                                                 │     SUCCESSFACTORS)  │
                                                 └──────────┬───────────┘
                                                            │ FetchContext(endpoint)
                                                            ▼
                                    ┌───────────────────────────────────────────┐
                                    │ SuccessFactorsStrategy.fetch(context)      │
                                    │  ├─ isClassicBoard(url)? ──(no)───────────▶│
                                    │  ├─ buildSearchUrl(endpointUrl, 0)  [NEW]  │
                                    │  ├─ fetchSearchPage(...) → parseTotalCount  │
                                    │  ├─ paginate fetchSearchPage → parseListings│
                                    │  └─ per listing: fetchDetail(url)  [NEW]    │
                                    │       ├─ parseDescription(html)             │
                                    │       ├─ parsePostedDate(html)   [NEW]      │
                                    │       └─ parseStreetAddress(html) [NEW]     │
                                    └───────────────┬───────────────────────────┘
                                                    │ WebClient (GET, 45s timeout,
                                                    │  150ms detail delay)
                                                    ▼
                                        https://jobs.fraunhofer.de
                                   /search/?q=Software&...&startrow=N   (25/page)
                                   /job/{City}-{Title}-{Zip}/{reqId}/    (microdata)
                                                    │
                                                    ▼ (RawAggregatorJob list)
                                    FetchResult.success(jobs, elapsed)
                                                    │
                                                    ▼
                              CrawlService persists JobPosting →
                              RoleFilter / Lingua / dedup / scoring / digest  (unchanged)
```

Description:

- **Dispatch is unchanged.** The Fraunhofer endpoint is typed `SUCCESSFACTORS`, so `StrategyRegistry` already routes it to `SuccessFactorsStrategy`. The strategy's `isClassicBoard(url)` returns `false` for `jobs.fraunhofer.de` (no `careerN.successfactors.*` host and no `company=` param), so execution enters the existing CSB HTML path — no new strategy class, no new `AtsType`.
- **The one structural change** is that the CSB path today hardcodes its search URL and fetches each detail page for description only. Phase 1 makes the search URL param-driven from the endpoint URL; Phase 4 makes the single detail fetch return description *plus* `postedDate` and `streetAddress` so no second HTTP request is introduced.
- **Boundary:** the strategy emits `RawAggregatorJob` records only. It never touches persistence, filters, or scoring — those consume the same `FetchResult` as every other ATS strategy.
- **Backward compatibility:** endpoints whose URL has no query params (e.g. `https://jobs.example.com`) fall back to the existing defaults `q=&locationsearch=Germany&locale=en_US`, so all pre-existing `SUCCESSFACTORS` CSB endpoints behave identically.

## Interfaces

### SuccessFactorsStrategy (modified members)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `fetch(FetchContext)` | `FetchContext` (has `endpoint()`) | `FetchResult` | Existing entrypoint. Branches classic vs CSB. CSB loop now reads search params from `endpoint.getUrl()` and builds jobs from `JobDetail` | Returns `FetchResult.empty/protectedEndpoint/error` via existing catch blocks (403→`protectedEndpoint`, 401→`protectedEndpoint`, other→`error`) |
| `buildSearchUrl(String endpointUrl, int startRow)` **[NEW]** | raw endpoint URL, row offset | `String` (full search URL) | Pure URL constructor. Extracts `q`, `locationsearch`, `locale` from the endpoint URL query string (fallback defaults `""`, `"Germany"`, `"en_US"`), then returns `origin + "/search/?q=..&locationsearch=..&locale=..&startrow=" + startRow` | Returns valid URL even when endpoint URL is a bare origin (no `/search` path); non-URL input handled by caller's try/catch |
| `fetchSearchPage(String endpointUrl, int startRow)` **[SIGNATURE CHANGE]** | endpoint URL, row offset | `String` (HTML) | Delegates to `buildSearchUrl(...)` then `webClient.get().uri(url).retrieve().bodyToMono(String.class).block(REQUEST_TIMEOUT)` | WebClient errors surface to `fetch`'s catch |
| `parseTotalCount(String html)` (unchanged) | HTML | `int` | Tries `ARIA_TOTAL_PATTERN`, `SHOWING_TOTAL_PATTERN`, then strips `</?b>` and matches `TOTAL_COUNT_PATTERN` (`Results 1 – 25 of 839`) | `NumberFormatException` → `0` |
| `parseListings(String html, String baseUrl)` (unchanged) | HTML + base URL | `List<JobListing>` | Selects `tr` rows via `a[href~=/job/.+/\\d+/]`, `extractExternalId` from last numeric segment, city from `td` col 2 (fallback `extractLocationFromUrl`) | Malformed rows skipped with debug log |
| `parseDescription(String html)` (unchanged) | detail HTML | `String \| null` | First selector `.jobdescription` (matches Fraunhofer `span.jobdescription`), then `.job-description`, `[class*=jobDescription]`, `.contentWithSidePanel__content`, `main`, `#content` | Returns `null` if no container |
| `parsePostedDate(String html)` **[NEW]** | detail HTML | `LocalDate \| null` | Selects `meta[itemprop=datePosted]`, parses `content` with `DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)` → `ZonedDateTime.toLocalDate()` | Parse failure → `null` (never throws; job not failed) |
| `parseStreetAddress(String html)` **[NEW]** | detail HTML | `String \| null` | Selects `meta[itemprop=streetAddress]`, returns trimmed `content` (e.g. `"Aachen, DE, 52074"`) | `null` when absent/blank |
| `fetchDetail(String jobUrl)` **[NEW, replaces `fetchDescription` usage]** | job URL | `JobDetail` record | Fetches detail page once (`fetchDetailPage`), returns `JobDetail(parseDescription, parsePostedDate, parseStreetAddress)` | HTTP/parse errors → `JobDetail(null, null, null)` with debug log |
| `fetchDetailPage(String url)` (unchanged) | URL | `String \| null` | `webClient.get().uri(url)...block(REQUEST_TIMEOUT)` | Errors surface to caller |

Nested records: `JobListing(String externalId, String title, String location, String url)` (existing); `JobDetail(String description, LocalDate postedDate, String streetAddress)` **[NEW]**.

### `fetch` loop job assembly (behavioral spec)

For each `JobListing`:
- `location` = `listing.location()` if non-blank, else `detail.streetAddress()` (Phase 4 fallback).
- `RawAggregatorJob(externalId, title, null /*companyName*/, location, detail.description(), listing.url(), detail.postedDate(), null, null, null, null)`.

`companyName` stays `null` — `CrawlService` assigns company from `endpoint.getCompany()` and `source` from `JobSource.fromAtsType(SUCCESSFACTORS)` (existing behavior).

## Data Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Admin Controller | `POST /api/admin/crawl/{endpointId}` | CrawlService |
| 2 | CrawlService | Load endpoint, `strategyRegistry.getStrategy(SUCCESSFACTORS)` | SuccessFactorsStrategy.fetch |
| 3 | SuccessFactorsStrategy | `isClassicBoard(url)` → false; `buildSearchUrl(url, 0)` → `/search/?q=Software&locationsearch=Germany&locale=en_US&startrow=0` | fetchSearchPage (page 1) |
| 4 | SuccessFactorsStrategy | `parseTotalCount(page1Html)` → 162; `totalPages = ceil(162/25) = 7` | parseListings(page1) + loop pages 2–7 |
| 5 | SuccessFactorsStrategy | `parseListings` → `JobListing` per `tr` (externalId = reqId, city = col 2) | fetchDetail per listing |
| 6 | SuccessFactorsStrategy | `fetchDetail` → `JobDetail(description, postedDate, streetAddress)` (one GET, 150ms delay between) | RawAggregatorJob assembly |
| 7 | SuccessFactorsStrategy | `FetchResult.success(jobs, elapsed)` | CrawlService |
| 8 | CrawlService | Persist `JobPosting` (`source=SUCCESSFACTORS`, `company=endpoint.company`) | Pipeline (filters → scoring → digest) |

**Error Flows**:

- Page fetch failure mid-pagination: caught per-page, logged, loop continues with remaining pages (existing behavior).
- Detail fetch failure: `fetchDetail` returns `JobDetail(null,null,null)`; job still emitted with `null` description/date (existing tolerance) — description is backfillable later.
- Board 403/401: `fetch` returns `FetchResult.protectedEndpoint(elapsed)`; endpoint marked `PROTECTED`.
- `parsePostedDate` failure: `postedDate` stays `null` (job never dropped; `JobPostingRepository` has a backfill query for null `postedDate`).
- Blank search response / `totalCount == 0`: `FetchResult.empty`.

## Data Model

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| `Company` (table `company`) | `id UUID`, `name`, `normalized_name`, `domain`, `country`, `is_active`, `status`, `discovered_via`, `priority_score`, … | one-to-many `CareerEndpoint` | `normalized_name` unique |
| `CareerEndpoint` (table `career_endpoint`) | `id UUID`, `company_id`, `url VARCHAR(1024)`, `ats_type VARCHAR(50)`, `ats_slug`, `ats_shard_id`, `confidence`, `verified`, `is_active`, `last_crawl_status`, `last_crawled_at`, `crawl_frequency_hours`, `source`, `created_at` | many-to-one `Company` | `company_id` FK NOT NULL; `ats_type` NOT NULL; `url` NOT NULL. **Note: `extraction_method` column was dropped in changelog 016 — do not reference it.** |
| `JobPosting` (table `job_posting`) | `source`, `endpoint_id`, `external_id`, `title`, `company_id`, `location`, `location_city`, `description`, `apply_url`, `posted_date`, `raw_content`, … | FK `endpoint_id`, `company_id` | unique `(source, external_id)` |

### Seed values (changelog `020-seed-fraunhofer.sql`)

```sql
--liquibase formatted sql
--changeset jobhunter:020-seed-fraunhofer

INSERT INTO company (id, name, normalized_name, domain, country, is_active, status, discovered_via, priority_score)
VALUES ('a0000052-0000-0000-0000-000000000052', 'Fraunhofer-Gesellschaft', 'fraunhofer',
        'fraunhofer.de', 'Germany', true, 'ACTIVE', 'MANUAL', 65);

INSERT INTO career_endpoint (company_id, url, ats_type, ats_slug, confidence, verified, is_active, crawl_frequency_hours, source)
VALUES ('a0000052-0000-0000-0000-000000000052',
        'https://jobs.fraunhofer.de/search/?q=Software&locale=en_US',
        'SUCCESSFACTORS', 'fraunhofer', 'HIGH', true, true, 24, 'MANUAL');
```

- Column list matches the **current** `career_endpoint` schema (post-016: no `extraction_method`).
- `crawl_frequency_hours = 24` per plan (162 detail fetches × ~150 ms ≈ 25 s of detail work per run).
- `q` is editable in the row without code change (recall tuning knob, see Risks).
- Changelog must also be registered in `master.xml` via `<include file="db/changelog/020-seed-fraunhofer.sql"/>`.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Reuse `SuccessFactorsStrategy` CSB path | No new strategy class / `AtsType` value | Live inspection confirmed existing selectors already match Fraunhofer markup | New dedicated strategy | More code, registry/config churn; existing selectors were verified compatible |
| Board-side pre-filter `q=Software` | Seed URL carries `q=Software` (162/839) | Avoid fetching ~677 non-SWE research roles (PhD, student, science); precise title gate stays in `RoleFilter` | Crawl all 839 | 4× detail-fetch cost; RoleFilter already precise but board filter saves load + noise |
| Query-param passthrough with defaults | `buildSearchUrl` reads `q`/`locationsearch`/`locale` from endpoint URL, falls back to `""`/`"Germany"`/`"en_US"` | R4; removes hardcoded `locationsearch=Germany` debt; keeps existing endpoints unchanged | Always hardcode | Breaks non-German CSB sites + Fraunhofer's `q` |
| Single detail fetch returns rich record | `fetchDetail` → `JobDetail` (description + postedDate + streetAddress) | Avoids a second HTTP request per job for microdata | Two fetches (description, then microdata) | 162 extra requests + doubled polite-delay cost |
| Microdata via Jsoup `meta[itemprop]` | `parsePostedDate`, `parseStreetAddress` selectors | No JSON-LD present; microdata is the only structured date/address source | AI extraction | Out of scope + costly; site is server-rendered |
| `postedDate` parse with strict formatter | `EEE MMM dd HH:mm:ss zzz yyyy`, `Locale.ENGLISH`, `ZonedDateTime.toLocalDate()` | Matches `java.util.Date.toString` format observed on detail pages | `parseIsoDate` (ISO only) | Wrong format; would always return null |
| Seed via Liquibase (not runtime discovery) | New changelog `020` | Idempotent, deterministic, follows `002-seed-companies.sql` convention | Manual curl insert | Not reproducible across envs |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| `q=Software` is full-text (title+description), so some non-SWE roles pass the board filter | Noise in crawl set | Med | `RoleFilter` title include-patterns (existing `profile.yaml`) are the precise gate; both reference jobs pass via `software` |
| Keyword recall misses future SW roles whose title/desc lacks "software" | Missed relevant jobs | Med | `q` is a DB column, editable without code change; acceptable vs 839 full crawl |
| Default `locationsearch=Germany` applied to Fraunhofer (URL lacks it) | Could theoretically drop non-DE postings | Low | Live-verified harmless (does not reduce 839 total); `q=Software` subset effectively DE-centric |
| `datePosted` microdata format drift or locale change | `postedDate` stays null | Low | Strict parse, failure → `null` (never drop job); backfill query exists for null `posted_date` |
| Fraunhofer markup drift (CSB template update) | Selectors regress silently | Low | Fixture tests lock in current shapes; health report surfaces empty crawls |
| Detail-fetch rate limiting (162 sequential GETs) | 429 / throttling | Low | Existing 150ms `DETAIL_FETCH_DELAY_MS`; per-page error tolerance; `crawl_frequency_hours=24` |
| Duplicate seed on re-run | Constraint violation | Low | Single `changeset` id (`jobhunter:020-seed-fraunhofer`) makes Liquibase run once |

## Test Plan

### Unit Tests (extend `SuccessFactorsStrategyTest`, fixture-based, no network)

Mock strategy: existing `WebClient` mock chain (`webClient.get() → requestHeadersUriSpec.uri(anyString()) → requestHeadersSpec.retrieve() → responseSpec.bodyToMono(...)`). Package-private `parse*`/`buildSearchUrl` methods invoked directly without mocks.

Fixtures to add (captured live HTML, sanitized, stored under test resources or inline text blocks):

- `fraunhofer-search-page.html` — pagination label `Results <b>1 – 25</b> of <b>162</b>`, `tr.data-row` listings with `/job/{City}-{Title}-{Zip}/{reqId}/` links, city in `td` col 2.
- `fraunhofer-detail-page.html` — `span.jobdescription` description, `meta[itemprop=datePosted]` = `"Tue Sep 08 02:00:00 UTC 2026"`, `meta[itemprop=streetAddress]` = `"Aachen, DE, 52074"`.

Key scenarios:

| # | Test | Method under test | Expected |
|---|------|-------------------|----------|
| 1 | `parseTotalCount` on Fraunhofer fixture (en-dash + `<b>` variant) | `parseTotalCount` | `162` |
| 2 | `parseListings` yields 25 listings; numeric `externalId`, city from col 2, absolute URL | `parseListings` | `25` rows, `externalId` matches `\d+`, `url` starts `https://jobs.fraunhofer.de/job/` |
| 3 | `parseDescription` extracts text from `span.jobdescription` | `parseDescription` | non-null, contains description prose |
| 4 | `buildSearchUrl` with `?q=Software&locale=en_US` → pagination carries `q=Software`, `locale=en_US`, `locationsearch=Germany` (default), `startrow=25` | `buildSearchUrl` | exact URL string assertion |
| 5 | `buildSearchUrl` with bare origin (no params) → `q=&locationsearch=Germany&locale=en_US` (unchanged defaults) | `buildSearchUrl` | exact URL string assertion (backward compat) |
| 6 | `parsePostedDate` on `datePosted` microdata → `LocalDate.of(2026, 9, 8)` | `parsePostedDate` | correct date |
| 7 | `parsePostedDate` malformed/absent → `null` (no throw) | `parsePostedDate` | `null` |
| 8 | `parseStreetAddress` on fixture → `"Aachen, DE, 52074"`; absent → `null` | `parseStreetAddress` | correct value / `null` |
| 9 | `fetch` end-to-end (mocked) with `q=Software` endpoint URL paginates 2 pages, `RawAggregatorJob.postedDate` populated, location falls back to `streetAddress` when cell blank | `fetch` | `SUCCESS`, postedDate set, location fallback applied |
| 10 | `fetch` with endpoint URL lacking `q` keeps existing behavior | `fetch` | no regression; existing tests (e.g. `extract_singlePageExtraction`) still green |

Mocks: `responseSpec.bodyToMono(String.class)` sequenced via `.thenReturn(...)` per call (search page 1, search page 2, detail pages). No real HTTP.

Coverage target: keep existing coverage; new branches (`buildSearchUrl`, `parsePostedDate`, `parseStreetAddress`, `JobDetail` assembly) covered.

### Integration Tests (manual, live)

- After seeding + migration: `POST /api/admin/crawl/{endpointId}` against `jobs.fraunhofer.de`.
- Verify job count ≈ 162; confirm reference reqIds `1372103633` and `887698201` present with title, city, description, apply URL.
- `GET /api/admin/health` shows the endpoint `SUCCESS` with expected count.

### End-to-End Tests (user journeys)

- New Fraunhofer jobs appear in `/api/jobs/today` and `/api/jobs?skill=software` with descriptions.
- Both reference roles surface under the software-security profile; score above threshold, reach digest.
- No regression: existing SUCCESSFACTORS endpoints (e.g. Ottobock) still crawl with unchanged behavior.

### Non-Functional Tests

- Performance: full crawl completes in reasonable time (162 detail fetches × ~150 ms delay ≈ 25 s detail phase + pagination); no unbounded memory (listings bounded by 162).
- Politeness: `DETAIL_FETCH_DELAY_MS=150` between detail fetches; `robots.txt` allows `/search/` and `/job/`.
- Resilience: mid-pagination and per-detail failures don't abort the whole crawl; `postedDate` parse failure degrades gracefully to `null`.
- No config changes: `profile.yaml`, `application.yaml`, `AtsType` enum untouched.
