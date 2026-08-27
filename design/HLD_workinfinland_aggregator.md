# HLD: WorkInFinland Aggregator Source

## Tech Stack

| Category  | Technology         | Purpose                                              |
| --------- | ------------------ | ---------------------------------------------------- |
| Language  | Java 21            | Matches existing API codebase                        |
| Framework | Spring Boot 3.3.5  | DI, component scan, WebClient                        |
| HTTP      | WebClient          | Non-blocking GET to WorkInFinland public jobs API    |
| JSON      | Jackson ObjectMapper | Parse JSON response envelope                       |
| Config    | Spring Boot YAML   | Source config via `AggregatorSourceProperties`       |
| Testing   | JUnit 5 + WireMock | Mock HTTP, verify pagination/fan-out/dedup logic     |

## Components

| Component                   | Responsibility                                                    | Dependencies                      |
| --------------------------- | ----------------------------------------------------------------- | --------------------------------- |
| `WorkInFinlandStrategy`     | Fetch jobs via GET `/api/jobs/`, paginate, category fan-out, dedup by `externalUrl` | WebClient, ObjectMapper           |
| `JobSource` (mod)           | New `WORK_IN_FINLAND` enum value + add to `AGGREGATORS` list       | None                              |
| `DiscoverySource` (mod)     | New `WORK_IN_FINLAND` enum value                                   | None                              |
| `application.yaml` (mod)    | Source entry with strategy, categories, limit, max-results         | AggregatorSourceProperties        |
| `AggregatorDescriptionEnricher` (existing) | Backfills null descriptions by fetching `applyUrl` via Jsoup | WebClient, JobPostingRepository   |

> **No `YamlSourceConfig` change** (unlike `BuiltInEuropeStrategy`): `config.categories` is read directly from `context.config()` inside the strategy, not routed through `buildContext().keywords()`.

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                       PipelineScheduler                           │
│   [iterates dynamicSources, includes workinfinland]              │
└─────────────────────────────┬────────────────────────────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  AggregatorIngestionService   │
               │  ingest(workinfinlandSource)  │
               └──────────────┬───────────────┘
                              │ source.strategy().fetch(context)
                              ▼
               ┌──────────────────────────────────────────────────┐
               │           WorkInFinlandStrategy                   │
               │  (implements FetchStrategy, name="workinfinland") │
               └──────┬──────────────────────────┬─────────────────┘
                      │                          │
        For each category slug              Dedup by externalUrl
        in config.categories()              (LinkedHashMap) across
        (fetch-all if empty)                all categories/pages
                      │                          │
                      ▼                          │
        ┌───────────────────────────┐            │
        │  GET /api/jobs/           │            │
        │  ?limit={limit}           │            │
        │  &page={page}             │            │
        │  &category={slug}         │            │
        │                           │            │
        │  Paginate until:          │            │
        │  - page > totalPages      │            │
        │  - maxResults hit         │            │
        └─────────────┬─────────────┘            │
                      │ jobs[] JsonNode           │
                      └──────────────────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  Map → RawAggregatorJob       │
               │  FetchResult.success(jobs)    │
               └──────────────┬───────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  Existing Filter Chain        │
               │  Language → Role → Location   │
               │  → YOE → Visa → Dedup        │
               └──────────────┬───────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  AggregatorDescriptionEnricher│
               │  (backfills null description  │
               │   via applyUrl fetch)         │
               └──────────────────────────────┘
```

**Description:** `WorkInFinlandStrategy` is a `@Component` implementing `FetchStrategy`. It reads `url` from `context.config()` (injected by `DynamicSourceConfigLoader`) and paginates `GET {url}/api/jobs/` with `limit`, `page` (1-based), and optional `category` params. When `config.categories` is present it fans out per category slug; otherwise it fetches the unfiltered listing. All collected job nodes are deduplicated by `externalUrl` in insertion order, then mapped to `RawAggregatorJob` with a derived `externalId` and returned as a `FetchResult`. Because the list API exposes no description/posted-date/salary, those fields are null at ingestion; the existing `AggregatorDescriptionEnricher` later fetches `applyUrl` to backfill descriptions. Downstream filter chain, scoring, and persistence are unchanged.

## Interfaces

### FetchStrategy (existing - implemented by WorkInFinlandStrategy)

| Method            | Input        | Output       | Behavior                                          | Errors                   |
| ----------------- | ------------ | ------------ | ------------------------------------------------- | ------------------------ |
| `name()`          | none         | `String`     | Returns `"workinfinland"`                          | None                     |
| `supportedTypes()`| none         | `Set<AtsType>` | Returns empty set (aggregator-only)              | None                     |
| `fetch(FetchContext)` | FetchContext | `FetchResult` | Fan-out categories, paginate, dedup, map to jobs | `FetchResult.error(msg)` |

### WorkInFinlandStrategy (internal methods)

| Method              | Input                                      | Output                 | Behavior                                                              | Errors                            |
| ------------------- | ------------------------------------------ | ---------------------- | --------------------------------------------------------------------- | --------------------------------- |
| `fetchCategory`     | `String category (nullable), int maxResults, String baseUrl` | `List<JsonNode>` | GET paginated requests for one category until `totalPages`/limit       | Throws on HTTP/parse error        |
| `mapJob`            | `JsonNode`                                 | `RawAggregatorJob`     | Extract fields per mapping table                                      | Returns null if no title/externalUrl |
| `deriveExternalId`  | `String externalUrl`                       | `String`               | Return URL as-is if ≤255 chars, else SHA-256 hex                      | None                              |
| `readIntConfig`     | `String key, int default`                  | `int`                  | Parse integer from `context.config()` (values are Strings)            | Falls back to default on parse fail |
| `readDelayMs`       | none                                       | `long`                 | Read optional `delayBetweenPagesMs` config                             | Falls back to 0                  |

## Data Flow

| Step | Component                     | Action                                                                 | Next                         |
| ---- | ----------------------------- | ---------------------------------------------------------------------- | ---------------------------- |
| 1    | PipelineScheduler             | Iterates `dynamicSources`, picks workinfinland                         | AggregatorIngestionService   |
| 2    | AggregatorIngestionService    | Calls `source.buildContext()` → `FetchContext` (config map w/ url)     | WorkInFinlandStrategy        |
| 3    | WorkInFinlandStrategy         | Resolve categories from `config.categories` (empty → fetch all)        | Self (category loop)         |
| 4    | WorkInFinlandStrategy         | For each category: GET `/api/jobs/?limit=L&page=1&category=slug`       | Self (pagination loop)       |
| 5    | WorkInFinlandStrategy         | Increment `page` until `page > totalPages` or `maxResults` reached     | Self (next page/category)    |
| 6    | WorkInFinlandStrategy         | Dedup all collected nodes by `externalUrl` (LinkedHashMap)             | Mapping                      |
| 7    | WorkInFinlandStrategy         | Map each node → `RawAggregatorJob`, skip null title/externalUrl        | FetchResult                  |
| 8    | WorkInFinlandStrategy         | Return `FetchResult.success(jobs, elapsed)`                            | AggregatorIngestionService   |
| 9    | Filter Chain                  | Language/Role/Location/YOE/Visa/Dedup filters applied                  | Persistence                  |
| 10   | Persistence                   | Upsert jobs into `job_posting` table under `WORK_IN_FINLAND`           | Post-ingestion enrichers     |
| 11   | AggregatorDescriptionEnricher | Fetch `applyUrl` HTML via Jsoup, backfill short/null descriptions      | Done                         |

**Error Flows:**
- Missing `url` config → `FetchResult.error("WorkInFinland config requires url", elapsed)`.
- HTTP 4xx/5xx from API → log error, return `FetchResult.error(message, elapsed)`. Pipeline skips source this cycle.
- Rate limit (429) → `RetryableWebClientFilter` retries with backoff; if exhausted → `FetchResult.rateLimited(elapsed)`.
- Timeout (30s per request) → WebClient throws, caught → `FetchResult.error(...)`.
- Malformed JSON → `ObjectMapper` throws, caught → `FetchResult.error(...)`.
- Late-page / late-category failure after partial success → retain already-collected jobs and return `FetchResult.success(partial)` (best-effort, mirrors `JobgetherStrategy`).
- Empty `jobs[]` across all categories → `FetchResult.empty(elapsed)`.

## Data Model

No new entities. Mapping from API response to existing `RawAggregatorJob` record:

### Field Mapping: API Response → RawAggregatorJob

| API Field              | RawAggregatorJob Field | Transform                                                                 |
| ---------------------- | ---------------------- | ------------------------------------------------------------------------- |
| `externalUrl`          | `externalId`           | Direct string if ≤255 chars; else SHA-256 hex digest (64 chars)           |
| `externalUrl`          | `applyUrl`             | Direct string (source-board posting URL)                                  |
| `title`                | `title`                | Direct string                                                             |
| `employer.name`        | `companyName`          | Direct string                                                             |
| `employer.city`        | `location`             | Direct string (e.g. "Espoo"); resolves to `FI` via location filter        |
| — (absent)             | `description`          | `null` (backfilled later by `AggregatorDescriptionEnricher`)              |
| — (absent)             | `postedDate`           | `null` (no post date; `expireDate` is semantically different, see below)  |
| — (absent)             | `salaryMin`            | `null`                                                                    |
| — (absent)             | `salaryMax`            | `null`                                                                    |
| — (absent)             | `salaryCurrency`       | `null`                                                                    |
| Full JSON node         | `rawJson`              | `node.toString()`                                                         |

### Unused API fields (available for future use)
- `expireDate` — liveness/expiry signal, not a post date; kept in `rawJson` only. Could later drive job deactivation.
- `employer.imageUrl` — company logo; not stored.
- `categories[]` / `cities[]` (envelope-level) — filter metadata; not stored.

## Config Schema

### YAML entry in `application.yaml` under `aggregator.sources`

```yaml
- name: workinfinland
  strategy: workinfinland
  job-source: WORK_IN_FINLAND
  discovery-source: WORK_IN_FINLAND
  url: "https://www.workinfinland.com/api/jobs/"
  frequency-hours: 12 # informational only
  max-results: 800
  visa-exempt: true
  config:
    categories: "software-development,ict,deep-tech,engineering"
    limit: "50"
    delayBetweenPagesMs: "500"
```

### Config key semantics

| Key                   | Type   | Required | Default | Description                                                              |
| --------------------- | ------ | -------- | ------- | ------------------------------------------------------------------------ |
| `categories`          | String | No       | `""`    | Comma-separated category slugs. Empty → fetch unfiltered listing.        |
| `limit`               | String | No       | `50`    | Page size passed as `limit` query param.                                 |
| `delayBetweenPagesMs` | String | No       | `0`     | Optional sleep between page requests (rate-limit courtesy).              |
| `url`                 | String | Yes      | —       | API endpoint (injected by DynamicSourceConfigLoader).                    |
| `max-results`         | int    | No       | 50      | Total job cap across all categories.                                     |
| `visa-exempt`         | bool   | No       | false   | Marks source as targeting international candidates (skips visa filter).  |
| `frequency-hours`     | int    | No       | 12      | Informational only (pipeline runs all sources per tick).                 |

## Decisions

| Decision                              | Choice                                                       | Reason                                                                    | Alternatives                                            | Tradeoffs                                                              |
| ------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------- |
| Config key for categories             | `config.categories` read directly by strategy via `config()`  | Category slugs ≠ search keywords; avoids touching `YamlSourceConfig`      | Reuse `config.queries` + `buildContext().keywords()`      | More coupling to key name if reused; direct read is simpler + self-contained |
| Category fan-out + dedup              | Iterate `config.categories`, dedup by `externalUrl` in `LinkedHashMap` | Categories overlap (`ict` vs `software-development`); `externalUrl` is the only stable unique key | Dedup by hashed title+company | `externalUrl` is authoritative (points at source board) and doubles as apply URL |
| Stable `externalId` derivation        | `externalUrl` verbatim if ≤255 chars, else SHA-256 hex        | List API has no `id`; URL is unique + stable; `job_posting.external_id` is VARCHAR(255) | Always hash URL to 64 chars | Verbatim URLs are human-readable/joinable; hash only when necessary     |
| `postedDate` left null                | Do not map `expireDate` to `postedDate`                       | `expireDate` is an expiry signal, not a post date; mapping would fabricate a date | Use `expireDate` as `postedDate`                        | Null date is honest; scoring/sorting already tolerate null postedDate  |
| `description` left null               | Rely on `AggregatorDescriptionEnricher`                       | List response has no description; enricher fetches `applyUrl` HTML        | Scrape source boards inside strategy                     | Out of scope (explicitly); some boards JS-rendered → partial enrichment accepted |
| Pagination termination                | `page > totalPages` OR `maxResults` reached                   | Response envelope provides `totalPages`; `maxResults` bounds total work   | Stop on empty `jobs[]` page                             | Empty-page check is a good backstop but `totalPages` is authoritative here |
| `limit` upper bound unknown            | Default `50`, verify max at implementation, fall back smaller | Frontend uses 12; larger page reduces request count but may be rejected/truncated | Hardcode large `limit` (e.g. 500)                       | Smaller pages are safer and add only a few extra requests              |
| Delay between pages                    | Optional `delayBetweenPagesMs`, default 0                     | Public API may throttle; courtesy delay prevents 429 cascades             | No delay, rely on retry filter                          | Slight latency increase for polite crawling                            |

## Risks

| Risk                                       | Impact                                    | Likelihood | Mitigation                                                            |
| ------------------------------------------ | ----------------------------------------- | ---------- | --------------------------------------------------------------------- |
| API adds authentication                    | Source stops working                      | Low        | Monitor for 401/403; alert via health endpoint                        |
| API rate limits / anti-bot throttling      | Partial/no results                        | Medium     | `delayBetweenPagesMs`, `RetryableWebClientFilter` handles 429          |
| API schema changes field names             | Null fields, jobs skipped                 | Low        | `rawJson` preserved; log warnings on null title/externalUrl           |
| `limit` rejected/truncated at large values | Silent truncation or HTTP error           | Medium     | Verify max `limit` at impl time; fall back to smaller page size       |
| Category slugs change or overlap           | Duplicate or missed jobs                  | Medium     | Dedup by `externalUrl`; slugs configurable in YAML                     |
| External URLs exceed VARCHAR(255)          | DB constraint violation                   | Low        | SHA-256 hash fallback in `deriveExternalId`                           |
| JS-rendered source boards (jobly.fi)       | Partial description enrichment            | Medium     | Accept partial enrichment (existing behavior); monitor success rate   |
| Cross-source duplicates (Jobly/TheHub/etc.)| Same job from multiple sources            | High       | Existing fingerprint dedup (`title+company+location`) in ingestion    |
| Large result set memory pressure           | OOM on unfiltered fetch (≈1092 jobs)      | Low        | `max-results: 800` cap; category fan-out reduces per-request footprint |

## Test Plan

### Unit Tests

#### `WorkInFinlandStrategyTest`

| Scenario                                  | Setup                                                                 | Assertion                                                            |
| ----------------------------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Happy path - single category              | WireMock returns 2 pages, `totalPages=2`, 3 jobs total               | `FetchResult.success` with 3 jobs, correct field mapping             |
| Pagination stops at `totalPages`          | WireMock page 1 returns 50 jobs (`totalPages=2`), page 2 returns 25  | All 75 jobs returned; no page 3 requested                            |
| `max-results` cap                         | API has 1000 jobs, `max-results=50`                                  | Returns ≤50 jobs; no further pages fetched                            |
| Multi-category fan-out + dedup            | 2 categories return overlapping jobs (shared `externalUrl`)          | Deduped list contains each job once                                  |
| No categories → fetch all                 | `config.categories` absent                                           | Single request without `category` param                              |
| externalId ≤255 chars                     | `externalUrl` short                                                  | `externalId == externalUrl`                                          |
| externalId >255 chars                     | `externalUrl` 300+ chars                                             | `externalId` is 64-char SHA-256 hex                                   |
| Null title                                | One job missing `title`                                              | Job skipped, others still returned                                   |
| Null `externalUrl`                        | One job missing `externalUrl`                                        | Job skipped, others still returned                                   |
| API error (500)                           | WireMock returns 500                                                 | `FetchResult.error(...)` with message                                |
| Rate limit (429)                          | WireMock returns 429                                                 | `FetchResult.rateLimited(...)`                                       |
| Malformed JSON                            | WireMock returns invalid JSON                                        | `FetchResult.error(...)` with parse error                            |
| Empty response                            | `jobs: []`, `totalJobs: 0`                                           | `FetchResult.empty(...)`                                             |
| Late-page failure retains partial         | Page 1 succeeds, page 2 returns 500                                  | Partial jobs from page 1 returned (best-effort)                       |
| description/postedDate/salary null        | Normal job node without those fields                                 | All three mapped to null                                             |
| `delayBetweenPagesMs` honored             | config sets `delayBetweenPagesMs: 500`, 2 pages                      | ≥500ms elapsed between page 1 and page 2 requests                    |

### Integration Tests

Not required. No schema changes, no new Spring beans beyond the component-scanned strategy. Existing `DynamicSourceConfigLoaderTest` pattern validates YAML → `SourceConfig` wiring (strategy name resolution via `StrategyRegistry`, `JobSource.valueOf("WORK_IN_FINLAND")`, `DiscoverySource.valueOf("WORK_IN_FINLAND")`).

### End-to-End Verification

- After deploy: `curl -X POST localhost:8089/api/admin/crawl` triggers pipeline.
- Verify persistence: `SELECT count(*) FROM job_posting WHERE source = 'WORK_IN_FINLAND'` returns > 0.
- Verify enriched: jobs have non-null `applyUrl`/`externalId`; short/null descriptions become populated after `AggregatorDescriptionEnricher` runs.
- Health endpoint shows workinfinland source status.

### Non-Functional

| Concern     | Requirement                                                                    |
| ----------- | ------------------------------------------------------------------------------ |
| Performance | Bounded fetch: ≤ `max-results` jobs, page size `limit`; ~800 jobs ≈ 16 pages   |
| Resilience  | Single category/page failure doesn't abort entire fetch (partial retained)      |
| Memory      | ≤800 jobs × ~1KB JSON ≈ <1MB peak; no concern                                  |
| Idempotency | Re-running pipeline produces same results (dedup by derived `externalId`)       |
| Politeness  | `delayBetweenPagesMs` + `RetryableWebClientFilter` respect remote throttling    |

## File Change List

| File                                                                              | Change Type | Description                                            |
| --------------------------------------------------------------------------------- | ----------- | ------------------------------------------------------ |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/WorkInFinlandStrategy.java`  | **New**     | FetchStrategy implementation (~180 lines)              |
| `api/src/main/java/dev/jobhunter/model/enums/JobSource.java`                      | Modify      | Add `WORK_IN_FINLAND` to enum + `AGGREGATORS` list     |
| `api/src/main/java/dev/jobhunter/model/enums/DiscoverySource.java`                | Modify      | Add `WORK_IN_FINLAND` to enum                          |
| `api/src/main/resources/application.yaml`                                          | Modify      | Add source entry under `aggregator.sources`            |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/WorkInFinlandStrategyTest.java` | **New**   | Unit tests with WireMock (~180 lines)                  |

> `YamlSourceConfig.java` is **not** modified: the strategy reads `categories`/`limit`/`delayBetweenPagesMs` directly from `context.config()`, so no `buildContext()` enhancement is required.
