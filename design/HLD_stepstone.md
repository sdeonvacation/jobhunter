# HLD: StepStone Jobs Ingestion (Aggregator Strategy)

## Overview

Add a jobs-only `FetchStrategy` named `stepstone`. It harvests robots-compliant StepStone search results, incrementally removes known jobs, fetches detail pages, extracts schema.org `JobPosting` JSON-LD, and returns `RawAggregatorJob` records to the existing aggregator ingestion pipeline. The strategy is registered through the existing dynamic source configuration and does not use the discovery provider.

## Goals / Non-goals

### Goals

- Ingest StepStone jobs from `stepstone.de`, `stepstone.at`, `stepstone.nl`, and `stepstone.be` detail links.
- Use sequential, delayed HTTP requests with configurable timeouts and scrape limits.
- Use `/jobs/*?q=*` search URLs without pagination.
- Preserve stable numeric StepStone IDs where available and use a non-null URL hash fallback otherwise.
- Reuse existing filtering, cross-source enrichment, deduplication, persistence, and run accounting.
- Surface rate limiting and anti-bot responses distinctly enough for safe operational handling.

### Non-goals

- Repairing or changing `api/src/main/java/dev/jobhunter/discovery/StepStoneProvider.java`.
- Changing `DiscoveryService` or the existing discovery `stepstone` configuration.
- Implementing StepStone company/endpoint discovery.
- Pagination, headless-browser scraping, database migrations, or a new persistence model.

## Current State

- `FetchStrategy` defines `fetch(FetchContext)`, `supportedTypes()`, and `name()`.
- `InstaffoStrategy` and `SitemapScrapeStrategy` establish the WebClient, Jsoup, JSON-LD, delay, incremental-ID, rate-limit, and partial-result patterns.
- `AggregatorIngestionServiceImpl` invokes a strategy, then applies source-level deduplication, cross-source fingerprint enrichment, filters, company resolution, persistence, and `AggregatorRun` accounting.
- `JobSource.STEPSTONE` exists but is not in `JobSource.AGGREGATORS`; therefore it is not currently treated as an aggregator by ingestion behavior.
- `AtsDetector` already recognizes StepStone domains. This HLD does not alter that behavior.

## Tech Stack

| Category | Technology | Purpose |
| -------- | ---------- | ------- |
| Language | Java 21 | Existing API implementation and typed records |
| Framework | Spring Boot 3.3.5 | Component registration, dependency injection, configuration, and WebClient integration |
| HTTP | Spring WebClient | Sequential search/detail page retrieval with timeout and status handling |
| HTML parsing | Jsoup 1.18.1 | Search-anchor and `<h1>` extraction |
| JSON parsing | Jackson `ObjectMapper` | Schema.org `JobPosting` JSON-LD parsing |
| Database | PostgreSQL 16 via Spring Data JPA | Known external-ID lookup and normal aggregator persistence |
| Configuration | `application.yaml`, `Map<String, Object>` | Dynamic source registration and string-valued strategy tuning |
| Testing | JUnit 5 and WireMock | Strategy contract, HTTP fixture, parsing, and failure tests |

## Components

| Component | Responsibility | Dependencies |
| --------- | -------------- | ------------ |
| `StepStoneStrategy` (`api/src/main/java/dev/jobhunter/strategy/aggregator/StepStoneStrategy.java`) | Build search URLs, harvest valid detail links, deduplicate/cap candidates, fetch details, parse JSON-LD, and return `FetchResult` | `WebClient`, `JobPostingRepository`, Jsoup, Jackson |
| `FetchStrategy` | Minimal strategy boundary used by the registry and ingestion service | `FetchContext`, `FetchResult` |
| `StrategyRegistry` | Resolve configured strategy name `stepstone` to the Spring component | Spring component registry |
| Dynamic aggregator source | Supply source metadata, base URL, keywords, cities, and limits | `application.yaml`, `YamlSourceConfig` |
| `AggregatorIngestionServiceImpl` | Consume `FetchResult`, apply common filtering/enrichment/deduplication, and persist jobs | repositories, filters, enrichers |
| `JobPostingRepository` | Return known StepStone external IDs before detail scraping | PostgreSQL/JPA |
| `AggregatorRunRepository` | Record result status, counts, elapsed time, and error message | PostgreSQL/JPA |
| StepStone discovery provider | Existing discovery implementation; explicitly unchanged and not called by this flow | None in this scope |

## Proposed Architecture

```text
application.yaml
      |
      v
Dynamic Source Config -- strategy name --> StrategyRegistry
      |                                         |
      | FetchContext                             v
      +-------------------------------> StepStoneStrategy
                                             |        |
                                  WebClient  |        | JobPostingRepository
                                             v        v
                                  StepStone search  Known IDs
                                             |
                                      valid detail URLs
                                             |
                                             v
                                  StepStone detail pages
                                             |
                                      Jsoup + Jackson
                                             |
                                             v
                                      FetchResult
                                             |
                                             v
                             AggregatorIngestionServiceImpl
                              | filters/enrichment/dedup |
                              v                          v
                         JobPosting/Company       AggregatorRun
```

Description: The strategy owns only StepStone retrieval and normalization into the shared `RawAggregatorJob` contract. It does not persist jobs directly. The ingestion service remains the boundary for business filters, company resolution, cross-source matching, and database writes. `supportedTypes()` remains empty so the strategy is not selected for ATS endpoint crawls; it is selected only by the dynamic source's `strategy: stepstone` name.

## Interfaces

### `StepStoneStrategy`

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `name()` | None | `"stepstone"` | Provides the dynamic strategy key | None |
| `supportedTypes()` | None | Empty `Set<AtsType>` | Prevents ATS endpoint routing to the search strategy | None |
| `fetch(FetchContext)` | `config.url`, `keywords`, optional `config.cities`, limits, and timeout values | `FetchResult` | Executes search harvesting, incremental deduplication, and detail scraping | Returns `ERROR`, `RATE_LIMITED`, `EMPTY`, or successful/partial job results according to status rules |

### Shared strategy contracts

| Contract | Shape | Responsibility |
|----------|-------|----------------|
| `FetchContext` | endpoint, keywords, locations, max results/pages, `Map<String,Object> config` | Carries dynamic source input; StepStone uses search mode and config values |
| `RawAggregatorJob` | 11 fields: external ID, title, company, location, description, apply URL, date, salary min/max/currency, raw JSON | Canonical strategy-to-ingestion DTO |
| `FetchResult` | jobs, total found, extraction status, error message, elapsed duration | Communicates jobs and operational outcome without persistence concerns |

## Data Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Dynamic source configuration | Registers `stepstone`, `STEPSTONE`, base URL, query/city strings, and limits | `StrategyRegistry` |
| 2 | `StrategyRegistry` / source | Resolves the `StepStoneStrategy` and builds a search `FetchContext` | `StepStoneStrategy.fetch` |
| 3 | `StepStoneStrategy` | Validate non-empty keywords and read base URL/configuration | Search harvesting |
| 4 | Search harvesting | Build `/jobs/<slug>?q=<encoded-keyword>` or `/jobs/<slug>/in-<city>?q=<encoded-keyword>` URLs and fetch sequentially with headers and delay | Jsoup link parser |
| 5 | Link parser | Resolve relative/absolute anchors and retain only matching `stepstone.(de|at|nl|be)/stellenangebote--...-inline.html` URLs in a `LinkedHashSet` | Incremental deduplication |
| 6 | Deduplication stage | Extract `--(\d+)-inline.html` IDs, load `findExternalIdsBySource(JobSource.STEPSTONE)`, remove known IDs, and cap at `maxScrapePerRun` | Detail scraping |
| 7 | Detail scraper | Fetch each candidate sequentially using the same browser-like headers and delay | JSON-LD parser |
| 8 | JSON-LD parser | Select `@type: JobPosting`, map fields, retain raw JSON, and use `<h1>` title fallback | `RawAggregatorJob` list |
| 9 | `StepStoneStrategy` | Return success, empty, error, or rate-limited result with elapsed time and partial jobs where applicable | `AggregatorIngestionServiceImpl` |
| 10 | Ingestion service | Apply exact-source and fingerprint deduplication, filters, company resolution, and enrichers | Persistence |
| 11 | Repositories | Save `JobPosting`/`Company` and update `AggregatorRun` counts/status | API/dashboard/next scheduler cycle |

**Error Flows**: Invalid or empty query input returns `ERROR` before external calls. Search HTTP 429 returns `RATE_LIMITED` without detail scraping; search 403 is logged as an anti-bot/protected warning and returns an error/protected outcome without retrying aggressively. Detail 429 stops the detail loop and returns already parsed jobs; 404/410 skips the removed job; other detail failures increment an error count and continue. If rate limiting yields no jobs, return `RATE_LIMITED`; if partial jobs exist, return them as a successful result while logging the rate-limited condition. Unexpected exceptions are contained by the strategy where possible and by `AggregatorIngestionServiceImpl` at the fetch boundary.

## Contracts / Data Mapping

### Detail JSON-LD to `RawAggregatorJob`

| `RawAggregatorJob` field | StepStone source | Mapping rule |
|--------------------------|------------------|--------------|
| `externalId` | Detail URL | Numeric ID from `--(\d+)-inline.html`; otherwise non-null URL hash |
| `title` | `JobPosting.title` | Trimmed; fallback to non-blank `<h1>` |
| `companyName` | `hiringOrganization.name` | Blank values become null |
| `location` | `jobLocation[].address.addressLocality` | Join available localities with `", "`; support array and single object shapes |
| `description` | `JobPosting.description` | Preserve extracted text/HTML representation supplied by JSON-LD |
| `applyUrl` | Current detail URL | Preserve the `-inline.html` URL |
| `postedDate` | `datePosted` | Parse the date portion to `LocalDate`; invalid/missing values become null |
| `salaryMin` / `salaryMax` | `baseSalary.value.minValue`, `maxValue`, or scalar `value` | Persist only when `baseSalary.unitText` is `YEAR`; do not infer non-yearly magnitudes |
| `salaryCurrency` | `baseSalary.currency` | Set with yearly salary values; otherwise null |
| `rawJson` | Selected `JobPosting` node | Preserve the serialized JSON-LD object for traceability |

Pages with neither JSON-LD nor a recoverable title are skipped. Malformed unrelated JSON-LD blocks do not fail the whole run.

## Configuration

Add one entry under `aggregator.sources` in `api/src/main/resources/application.yaml`; all nested `config` values remain strings:

```yaml
- name: stepstone
  strategy: stepstone
  job-source: STEPSTONE
  discovery-source: STEPSTONE
  url: "https://www.stepstone.de"
  frequency-hours: 12
  max-results: 100
  config:
    queries: "java developer,backend engineer,spring boot,kotlin developer"
    cities: "berlin,muenchen,hamburg,frankfurt-am-main,koeln"
    delayBetweenMs: "1500"
    maxScrapePerRun: "40"
```

The strategy reads the base URL from `context.config().get("url")`, keywords from `context.keywords()`, and optional city slugs from `config.cities`. Search and detail timeout settings are configurable strategy values with implementation defaults. `frequency-hours` remains informational under the current pipeline scheduler. The existing discovery `stepstone` block is separate and must remain untouched.

## Data Model

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| `RawAggregatorJob` | `externalId`, title, company, location, description, apply URL, posted date, salary range/currency, raw JSON | Transient result consumed by ingestion | External ID must be non-null/non-blank before persistence; title required for emitted detail result |
| `JobPosting` | Source, external ID, title, company, location, description, URL, fingerprint, dates, salary, filter/enrichment state | Belongs to resolved `Company`; source is `STEPSTONE` | Exact source + external ID dedup; existing entity constraints and filters apply |
| `Company` | Name, normalized name, active/status, discovery source/timestamps | Referenced by `JobPosting` | Existing per-batch cache and normalized-name lookup apply |
| `AggregatorRun` | Source name, status, fetched/created/enriched/filtered/error counts, elapsed time, error message | One current run record per configured source name | Updated by common ingestion flow; no new table or migration |

## Deduplication

1. `LinkedHashSet` removes duplicate detail URLs across keywords and cities while preserving discovery order.
2. Numeric URL IDs are compared with `findExternalIdsBySource(JobSource.STEPSTONE)` before detail requests.
3. `maxScrapePerRun` limits new detail fetches, independently of the source `max-results` metadata.
4. `AggregatorIngestionServiceImpl` performs a second exact source+external-ID check and prevents duplicates within the returned batch.
5. Existing fingerprint matching can enrich an existing ATS posting instead of creating a second `JobPosting`.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Retrieval model | Search pages followed by detail pages | Produces actual jobs without relying on broken discovery integration | Repair discovery provider, sitemap, headless browser | Dependent on search markup and limited by robots/rate limits |
| Detail extraction | Schema.org `JobPosting` JSON-LD with `<h1>` title fallback | Stable semantic contract and existing project pattern | CSS class scraping, browser rendering | Some fields may be absent or change shape |
| Pagination | Excluded from v1 | Required `/jobs/*?q=*` pattern and approved scope avoid broader crawling | Page parameters or infinite-scroll emulation | Coverage depends on query/city breadth |
| Request pacing | Sequential requests and approximately 1500 ms default delay | Reduces load and anti-bot risk | Parallel fetches or no delay | Slower runs, especially at 40 detail pages |
| Persistence | Existing aggregator ingestion path | Keeps filters, dedup, enrichment, company handling, and run metrics consistent | Strategy-owned persistence | Strategy cannot independently commit partial database records |
| ATS routing | Empty `supportedTypes()` | This is a search aggregator, not endpoint crawl logic | Advertise `STEPSTONE` ATS support | Explicitly prevents accidental endpoint routing |

## Error Handling

| Condition | Strategy behavior | Run/accounting behavior |
|-----------|-------------------|-------------------------|
| Missing URL or empty queries | Do not call StepStone; return `ERROR` | Ingestion records an error run |
| Invalid numeric config | Use documented defaults | Log effective/defaulted behavior at debug or warn level |
| Search 429 | Stop search phase and return `RATE_LIMITED` | No destructive retry; next scheduled cycle retries |
| Search 403/interstitial | Log distinct anti-bot/protected warning; stop or return protected/error outcome | Source can be disabled by configuration without code change |
| Detail 429 | Stop detail phase, retain jobs already parsed | Partial jobs are ingested; rate-limit condition is logged |
| Detail 404/410 | Skip the removed listing | No run error for expected removal |
| Other HTTP/network failure | Continue remaining details and increment error count | Run becomes error only if no useful jobs are produced under common ingestion rules |
| Malformed/missing JSON-LD | Try title fallback; skip only if no title | Count/log skipped extraction as appropriate; no batch abort |
| Invalid date/salary | Set affected optional fields null; never guess | Job can still be ingested |
| Persistence/filter failure | Common ingestion catches per-job failures | Error count and `AggregatorRun` reflect failures |

## Testing

### Unit Tests

Create `api/src/test/java/dev/jobhunter/strategy/aggregator/StepStoneStrategyTest.java`.

- Verify `name()` and empty `supportedTypes()`.
- Mock `JobPostingRepository` and return known StepStone IDs for incremental dedup tests.
- Use WireMock-backed `WebClient` responses for search and detail fixtures.
- Verify encoded `?q=` URLs, optional city URL shape, sequential candidate handling, relative-link resolution, domain/path filtering, cross-city URL deduplication, numeric ID extraction, and URL-hash fallback.
- Verify complete JSON-LD mapping for title, company, location, date, salary, URL, and raw JSON.
- Verify yearly salary only, scalar salary support, missing optional fields, malformed JSON-LD, `<h1>` fallback, and untitled-page skipping.
- Verify known-ID skipping and `maxScrapePerRun` cap.
- Verify search/detail 429 behavior including partial results, 404/410 skipping, 403 anti-bot handling, other failures, and empty-query errors.
- Target high coverage of strategy branches, especially status handling and parser fallbacks; the exact project coverage threshold remains governed by the existing build.

### Integration Tests

- Verify strategy registration by name through `StrategyRegistry` and dynamic source construction.
- Verify a `FetchResult` from StepStone flows through `AggregatorIngestionServiceImpl` into `JobPosting`, `Company`, and `AggregatorRun` using repository/Testcontainers boundaries where practical.
- Verify `STEPSTONE` is included in aggregator source behavior and that existing filter, fingerprint enrichment, and exact-ID dedup paths receive the expected source.
- Verify no call is made to `StepStoneProvider` or discovery services by the aggregator path.

### End-to-End Tests

- With the API and PostgreSQL running, register the configured source, execute the manual StepStone aggregator crawl, and confirm the returned run completes without headless-browser dependencies.
- Confirm persisted rows have source `STEPSTONE`, non-blank external IDs, `-inline.html` apply URLs, and available title/location/date values.
- Confirm a subsequent run does not re-fetch known numeric IDs and that partial rate-limited runs retain already collected jobs.

### Non-Functional Tests

- Verify requests are sequential, use browser-like `User-Agent` and `Accept-Language`, honor the configured delay, and stop at the detail cap.
- Verify timeout settings bound stalled search/detail calls.
- Verify no credentials, cookies, or unrestricted redirects are required by the design.
- Exercise disablement by removing/commenting the dynamic source entry without changing discovery configuration.

## Observability

- Use the existing strategy logger with a `[stepstone]` prefix.
- Log search count, valid-link count, known-ID skips, cap skips, detail successes, skipped 404/410 pages, extraction skips, error count, rate-limited state, and elapsed time.
- Keep warning-level events for 429, 403/anti-bot responses, unexpected HTTP failures, and repository lookup failures; keep malformed optional JSON-LD field issues at debug/trace where possible.
- Use the existing `AggregatorRun` fields for status, fetched, created, enriched, filtered, errors, elapsed time, and error message.
- Treat a run with partial jobs and a detail 429 as operationally visible through logs and counts without discarding already parsed jobs.
- Verification endpoints remain the existing admin aggregator endpoints described in the plan. No new metrics or endpoint is required in this scope.

## Security / Compliance

- Restrict search harvesting to the approved `/jobs/*?q=*` URL form and do not add pagination parameters in v1.
- Honor the serialized delay and browser-like request headers; do not bypass anti-bot controls.
- Use URL/domain allowlisting for StepStone country domains and URL-pattern filtering to avoid following unrelated anchors.
- Do not introduce credentials, session automation, CAPTCHA handling, proxy rotation, or headless-browser execution.
- Preserve only the existing `rawJson` job metadata field needed by the aggregator contract; apply existing application data-retention and GDPR behavior to persisted records.
- Keep discovery-provider changes explicitly out of the trust and execution boundary.

## Risks / Trade-offs

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| StepStone search or JSON-LD markup changes | Fewer jobs or missing fields | Medium | URL-based selectors, semantic JSON-LD parsing, `<h1>` fallback, fixture tests, raw JSON retention |
| Anti-bot 403/interstitial or 429 | Incomplete/empty runs | Medium | User-Agent/language headers, delay, sequential requests, distinct logging, next-cycle retry, configurable source disablement |
| No pagination | Lower discovery coverage | High | Expand approved keyword/city configuration rather than adding page crawling in v1 |
| 40-detail default and pacing make runs slow | Long pipeline cycle | Medium | Configurable delay and cap; observe elapsed time before tuning |
| Salary units vary | Incorrect compensation data | Medium | Persist only `YEAR`; leave unsupported units null |
| German-language jobs filtered downstream | Fewer visible jobs | Medium | Treat as expected filter behavior, not a scrape failure; inspect filter decisions separately |
| URL hash fallback instability | Potential re-fetch if URL changes | Low | Prefer numeric ID; use stable canonical detail URL as fallback and retain URL in `applyUrl` |

## Rollout / Verification

1. Add the strategy and aggregator-list/config changes defined by the plan, without touching discovery code/config or schema.
2. Run `./gradlew compileJava compileTestJava` from `api/`.
3. Run `./gradlew test --tests "dev.jobhunter.strategy.aggregator.StepStoneStrategyTest"`, then the full `./gradlew test` suite.
4. Restart the API so component registration and YAML are loaded by the new process.
5. Verify source registration with the existing admin aggregator endpoint and execute the manual StepStone source crawl from the plan.
6. Inspect `AggregatorRun` and persisted rows for source, IDs, URLs, title/location/date, filtering, and error counts.
7. Repeat a crawl to verify known-ID and cross-city deduplication; observe 403/429 handling in logs without attempting to bypass controls.

## Open Decisions

- Exact search/detail timeout default values, beyond being configurable, should follow the implementation's existing WebClient timeout conventions.
- Whether a search 403 should map to `ExtractionStatus.PROTECTED` or `ERROR` should align with the final strategy implementation and existing admin status presentation; both must remain distinct from a successful empty run.
- The operational tuning values for query breadth, city breadth, delay, and `maxScrapePerRun` can be adjusted after observing real run duration and StepStone response behavior. Pagination remains a separate future decision.

## Architecture

The architecture above deliberately uses the existing strategy/ingestion abstraction rather than adding StepStone-specific persistence or discovery paths. This keeps implementation extensible: future aggregator strategies can reuse the same `FetchResult` and `RawAggregatorJob` contracts, while StepStone-specific URL and JSON-LD rules remain isolated in `StepStoneStrategy`.
