# HLD: VisaJobs (Affluent Indians Hub) Authenticated Aggregator Source

## Tech Stack

| Category  | Technology            | Purpose                                                             |
| --------- | --------------------- | ------------------------------------------------------------------- |
| Language  | Java 21               | Matches existing API codebase                                       |
| Framework | Spring Boot 3.3.5     | DI, component scan, shared WebClient bean                           |
| HTTP      | WebClient             | Non-blocking GET to Supabase PostgREST `/rest/v1/jobs` + POST to `/auth/v1/token` |
| JSON      | Jackson ObjectMapper  | Parse PostgREST array rows + refresh-token response                 |
| Config    | Spring Boot YAML      | Source config via `AggregatorSourceProperties`; secret via env var  |
| Auth      | Supabase auth-js token | `apikey` (public anon key) + `Authorization: Bearer <access_token>` |
| Testing   | JUnit 5 + WireMock    | Mock Supabase REST + auth endpoints; verify pagination/auth/dedup   |

## Components

| Component                     | Responsibility                                                                    | Dependencies                                |
| ----------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------- |
| `VisaJobsStrategy`            | Paginate `/rest/v1/jobs` via `offset`/`limit`, send `apikey`+`Bearer` headers, map rows → `RawAggregatorJob` | WebClient, ObjectMapper, VisaJobsTokenProvider |
| `VisaJobsTokenProvider`       | Store `refresh_token` (env), lazily obtain/refresh access token, inject Bearer; handle 401 refresh + revoked-token signal | WebClient, `@Value` env                    |
| `JobSource` (mod)             | New `VISAJOBS` enum value + add to `AGGREGATORS` list                              | None                                        |
| `DiscoverySource` (mod)       | New `VISAJOBS` enum value                                                          | None                                        |
| `application.yaml` (mod)      | Source entry under `aggregator.sources` (strategy, visa-exempt, supabase url, apikey, auth-url, page size) | AggregatorSourceProperties                  |
| `AggregatorIngestionServiceImpl` (existing) | Routing path providing L1 externalId skip, L5 `dedupHash`, fingerprint enrichment, filter chain | JobPostingRepository, JobFilterChain        |
| `DynamicSourceConfigLoader` (existing) | YAML → `SourceConfig` wiring; resolves `strategy: visajobs` via `StrategyRegistry` | AggregatorSourceProperties, StrategyRegistry |

> **No `YamlSourceConfig` change**: `VisaJobsStrategy` reads its keys (`apikey`, `auth-url`, `limit`, `visa-confirmed-only`, `delay-between-pages-ms`) directly from `context.config()`, mirroring `WorkInFinlandStrategy`.

## Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                         PipelineScheduler                              │
│  [crawl ATS endpoints ∥ aggregator sources — CONCURRENT, see Notes]    │
└───────────────┬─────────────────────────────┬──────────────────────────┘
                │                             │
                ▼ (async)                     ▼ (async)
┌───────────────────────────┐   ┌──────────────────────────────────────┐
│  CrawlService (direct)    │   │  AggregatorIngestionServiceImpl       │
│  ATS/CUSTOM endpoints     │   │  ingest(visajobsSource)               │
└───────────────────────────┘   └──────────────┬───────────────────────┘
                                               │ source.strategy().fetch(ctx)
                                               ▼
                              ┌────────────────────────────────────────┐
                              │           VisaJobsStrategy               │
                              │  (implements FetchStrategy,             │
                              │   name="visajobs", supportedTypes={})   │
                              └───────┬──────────────────────┬──────────┘
                                      │                      │
                        ┌─────────────┘                      │
                        ▼                                     ▼
        ┌────────────────────────────────┐      ┌──────────────────────────┐
        │ VisaJobsTokenProvider           │      │ GET {url}/rest/v1/jobs   │
        │  getAccessToken(authUrl)        │      │  ?select=*               │
        │  → cached, else POST            │      │  &hidden=eq.false        │
        │    /auth/v1/token               │      │  &visa_confirmed=eq.Yes  │
        │    ?grant_type=refresh_token    │      │  &order=posted.desc...   │
        │    {refresh_token}              │      │  &offset=N&limit=L       │
        │  returns access_token           │      │  headers:                │
        └───────────────┬────────────────┘      │   apikey: <anon-key>     │
                        │ Bearer token           │   Authorization: Bearer  │
                        └───────────────────────►│      <access_token>      │
                                                 └────────────┬─────────────┘
                                                              │ rows[] JsonNode
                                                              ▼
                                                 ┌──────────────────────────┐
                                                 │ Map → RawAggregatorJob    │
                                                 │ externalId←job_id         │
                                                 │ applyUrl←url (L5-critical)│
                                                 │ description←description_text│
                                                 │ FetchResult.success(jobs) │
                                                 └────────────┬─────────────┘
                                                              │
                                                              ▼
                                   ┌──────────────────────────────────────┐
                                   │  Dedup layers (existing)             │
                                   │  L1 source+externalId                │
                                   │  L5 dedupHash (source-scoped)        │
                                   │  fingerprint enrich (ATS/direct)     │
                                   │  filter-chain fingerprint SKIP (all) │
                                   └────────────┬─────────────────────────┘
                                                ▼
                                   ┌──────────────────────────────────────┐
                                   │  Filter chain (visa-exempt=true →    │
                                   │  visaSponsorship=UNKNOWN)            │
                                   │  → persist JobPosting (VISAJOBS)     │
                                   └──────────────────────────────────────┘
```

**Description:** `VisaJobsStrategy` is a `@Component` implementing `FetchStrategy`. It reads `url` (the PostgREST list endpoint, injected as `config.url` by `DynamicSourceConfigLoader`), the public `apikey`, and `auth-url` from `context.config()`. Before each fetch it asks `VisaJobsTokenProvider` for a valid access token (cached; silently refreshed via `POST /auth/v1/token?grant_type=refresh_token` when absent/expired). It paginates `GET /rest/v1/jobs?select=*&hidden=eq.false[&visa_confirmed=eq.Yes]&order=posted.desc.nullslast,job_id.desc&offset=N&limit=L` with per-request `apikey` and `Authorization: Bearer` headers (never a global WebClient default header, to avoid leaking the token to other sources). Rows are deduplicated by `job_id` in insertion order, mapped to `RawAggregatorJob` with `applyUrl` = row `url` (the original board URL), and returned as a `FetchResult`. Downstream dedup, filter chain, scoring, and persistence are unchanged.

**Concurrency note (ordering):** `PipelineScheduler` runs the direct-board crawl and all aggregator sources **concurrently** (no guaranteed order). Therefore "crawl visajobs after direct boards" cannot be enforced by scheduler ordering alone. The dedup design is order-tolerant instead: on ticks where a direct-board job already exists, the fingerprint enrichment path (below) enriches rather than inserts; and if a visajobs row inserts first, a later direct-board crawl **supersedes** it via `CrawlService.demoteAggregatorDupes`. This makes the pipeline self-convergent without scheduler changes.

## Interfaces

### FetchStrategy (existing — implemented by VisaJobsStrategy)

| Method             | Input        | Output            | Behavior                                        | Errors                        |
| ------------------ | ------------ | ----------------- | ----------------------------------------------- | ----------------------------- |
| `name()`           | none         | `String`          | Returns `"visajobs"`                            | None                          |
| `supportedTypes()` | none         | `Set<AtsType>`    | Empty set (aggregator-only)                     | None                          |
| `fetch(FetchContext)` | FetchContext | `FetchResult`    | Obtain token, paginate, dedup by `job_id`, map  | `FetchResult.error/rateLimited/protectedEndpoint` |

### VisaJobsStrategy (internal methods)

| Method                 | Input                                          | Output                 | Behavior                                                               | Errors                          |
| ---------------------- | ---------------------------------------------- | ---------------------- | ---------------------------------------------------------------------- | ------------------------------- |
| `fetch`                | FetchContext                                   | FetchResult            | Validate config, resolve token, paginate, map, return success/error     | error/rateLimited/protectedEndpoint |
| `fetchPage`            | `String url, int offset, int limit, boolean visaOnly, String token` | `List<JsonNode>`       | GET one page with `apikey`+`Bearer`; throws on HTTP 401/parse          | WebClientResponseException      |
| `mapJob`               | JsonNode                                       | RawAggregatorJob       | Map row per field-mapping table; skip if no title/url/job_id            | null (skip)                     |
| `deriveExternalId`     | String jobId                                   | String                 | Verbatim if ≤255 chars, else SHA-256 hex (reused from WorkInFinland)    | None                            |
| `buildListUrl`         | base, offset, limit, visaOnly                  | String                 | Assemble PostgREST query string                                        | None                            |
| `readIntConfig` / `readBoolConfig` / `readDelayMs` | key, default | int/bool/long         | Parse from `context.config()` (values are Strings)                      | Fall back to default            |

### VisaJobsTokenProvider (new `@Component`)

| Method            | Input               | Output  | Behavior                                                                    | Errors                          |
| ----------------- | ------------------- | ------- | --------------------------------------------------------------------------- | ------------------------------- |
| `getAccessToken`  | `String authUrl`    | String  | Return cached access token if not near expiry (5-min margin); else refresh  | `VisaJobsRefreshTokenException` |
| `invalidate`      | none                | void    | Clear cached token (forces refresh on next `getAccessToken`)                | None                            |

**Refresh flow:** `POST {authUrl}?grant_type=refresh_token` body `{"refresh_token":"<env value>"}` → response `{ access_token, refresh_token, expires_in, ... }`. On non-2xx (invalid grant / revoked token), throw `VisaJobsRefreshTokenException`; the strategy maps this to `FetchResult.protectedEndpoint(elapsed)` — the existing `ExtractionStatus.PROTECTED` ("requires authentication") signal that the manual re-login is required. This is the same signal already used by `SuccessFactorsStrategy`, `WorkdayStrategy`, `StepStoneStrategy`, and `TeamtailorStrategy` for auth-gated endpoints.

## Data Flow

| Step | Component                     | Action                                                                  | Next                          |
| ---- | ----------------------------- | ----------------------------------------------------------------------- | ----------------------------- |
| 1    | PipelineScheduler             | Runs direct-board crawl + aggregator sources (concurrent)                | AggregatorIngestionServiceImpl |
| 2    | AggregatorIngestionServiceImpl | `source.strategy().fetch(source.buildContext())`                       | VisaJobsStrategy              |
| 3    | VisaJobsStrategy              | Read `url`, `apikey`, `auth-url`, `limit`, `visa-confirmed-only`         | VisaJobsTokenProvider         |
| 4    | VisaJobsTokenProvider         | Return cached access token or POST `/auth/v1/token?grant_type=refresh_token` | VisaJobsStrategy          |
| 5    | VisaJobsStrategy              | GET page `offset=0&limit=L` with `apikey` + `Bearer` headers             | Self (pagination loop)        |
| 6    | VisaJobsStrategy              | Dedup rows by `job_id`; stop when rows < limit or `max-results` reached  | Mapping                       |
| 7    | VisaJobsStrategy              | Map each row → `RawAggregatorJob` (applyUrl=url, description=description_text) | FetchResult            |
| 8    | VisaJobsStrategy              | `FetchResult.success(jobs, elapsed)`                                     | AggregatorIngestionServiceImpl |
| 9    | AggregatorIngestionServiceImpl | L1 externalId → L5 dedupHash → fingerprint enrich → filter-chain SKIP    | Persistence                   |
| 10   | Filter chain                  | visa-exempt=true → visaSponsorship=UNKNOWN; role/location/yoe/dedup      | Persistence                   |
| 11   | Persistence                   | Upsert `JobPosting` (source=VISAJOBS, dedupHash set, fingerprint set)    | Post-ingestion scoring        |

**Error Flows:**
- Missing `url` config → `FetchResult.error("VisaJobs config requires url", elapsed)`.
- Missing `apikey`/`auth-url` → `FetchResult.error(...)`. (Missing `VISAJOBS_REFRESH_TOKEN` env → token provider throws at construction/first refresh → surfaced as `PROTECTED`, never silent empty.)
- HTTP 401 on a page → invalidate cached token, refresh, retry **once**. Second 401 → `FetchResult.protectedEndpoint(elapsed)`.
- Refresh failure (revoked/expired refresh token) → `VisaJobsRefreshTokenException` → `FetchResult.protectedEndpoint(elapsed)` (distinct re-login signal).
- HTTP 429 → retried by `RetryableWebClientFilter` (3× backoff); if exhausted → `FetchResult.rateLimited(elapsed)`.
- HTTP 5xx → retried once by filter; if exhausted → `FetchResult.error(...)`.
- Malformed JSON / empty array → `FetchResult.error(...)` / `FetchResult.empty(elapsed)`.
- Late-page failure after partial success → retain already-collected jobs, return `FetchResult.success(partial)` (best-effort, mirrors `WorkInFinlandStrategy`).
- RLS policy change (anon-readable or paid tier) → 401/403 surfaces via the same paths; the anon-key-only probe (`[]` vs rows) remains a cheap health check.

## Data Model

No new entities. Mapping from Supabase `jobs` row to existing `RawAggregatorJob` record:

### Field Mapping: Supabase row → RawAggregatorJob

| Supabase field      | RawAggregatorJob field | Transform                                                                 |
| ------------------- | ---------------------- | ------------------------------------------------------------------------- |
| `job_id`            | `externalId`           | Verbatim if ≤255 chars, else SHA-256 hex (reuse `deriveExternalId`)       |
| `title`             | `title`                | Direct string                                                             |
| `company`           | `companyName`          | Direct string                                                             |
| `location`          | `location`             | Direct string                                                             |
| `description_text`  | `description`          | Direct string (full JD — **no enrichment fetch needed**)                  |
| `url`               | `applyUrl`             | Direct string — **original board apply URL; drives L5 `dedupHash` + fingerprint enrichment** |
| `posted`            | `postedDate`           | Parse ISO timestamp → `LocalDate` (null on parse failure)                 |
| `salary`            | `salaryMin/Max/Currency` | `null` — single free-text field of unknown format; preserved in `rawJson` |
| (whole row)         | `rawJson`             | `node.toString()`                                                          |

### Preserved-in-`rawJson` fields (not mapped; available for future use)

`remote`, `visa_confirmed`, `visa_type`, `seniority`, `experience`, `employment`, `tech_stack`, `snippet`, `source` (original aggregator: Arbeitnow/Mindrift/Jobgether/…), `ats`, `fetched_at`, `hidden`, `created_at`.

> `source` is the **origin board**, distinct from `JobSource.VISAJOBS`. It is deliberately not mapped (the job is ingested under `VISAJOBS`), but retained in `rawJson` for provenance.

## Config Schema

### YAML entry in `application.yaml` under `aggregator.sources`

```yaml
- name: visajobs
  strategy: visajobs
  job-source: VISAJOBS
  discovery-source: VISAJOBS
  url: "https://svupfnfszawowpkqhwdn.supabase.co/rest/v1/jobs"
  frequency-hours: 12        # informational only
  max-results: 2000          # cap across all pages
  visa-exempt: true
  config:
    apikey: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InN2dXBmbmZzemF3b3dwa3Fod2RuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODM0OTEyNjYsImV4cCI6MjA5OTA2NzI2Nn0.GM9Uq_-ruw3Hvs94WDNzg0oMv9ToiXfgXEyMwFu4--Q"
    auth-url: "https://svupfnfszawowpkqhwdn.supabase.co/auth/v1/token"
    limit: "100"
    visa-confirmed-only: "true"
    delay-between-pages-ms: "400"
```

The **refresh token is NOT in YAML/DB** — it is read from the environment variable `VISAJOBS_REFRESH_TOKEN` by `VisaJobsTokenProvider` via `@Value("${VISAJOBS_REFRESH_TOKEN:}")`, so the secret never enters source control or backups.

### Config key semantics

| Key                      | Type   | Required | Default | Description                                                                 |
| ------------------------ | ------ | -------- | ------- | --------------------------------------------------------------------------- |
| `url` (top-level)        | String | Yes      | —       | PostgREST list endpoint (injected by `DynamicSourceConfigLoader` as `config.url`) |
| `config.apikey`          | String | Yes      | —       | Public Supabase anon key (safe to commit).                                   |
| `config.auth-url`        | String | Yes      | —       | Supabase refresh-token endpoint (`/auth/v1/token`).                          |
| `VISAJOBS_REFRESH_TOKEN` | env    | Yes      | —       | Long-lived refresh token (secret; never committed).                          |
| `config.limit`           | String | No       | `100`   | Page size passed as `limit`. Verify Supabase `max-rows` cap at impl.         |
| `config.visa-confirmed-only` | String | No   | `true`  | Add `visa_confirmed=eq.Yes` server-side filter (reduces 36k→5.6k rows).      |
| `config.delay-between-pages-ms` | String | No   | `0`   | Polite delay between offset pages (rate-limit courtesy).                     |
| `max-results`            | int    | No       | `50`    | Total job cap across all pages (bounded by `context.maxResults()`).          |
| `visa-exempt`            | bool   | No       | `false` | `true` → skips visa filter; `visaSponsorship` stays `UNKNOWN`.               |
| `frequency-hours`        | int    | No       | `12`    | Informational only.                                                          |

## Decisions

| Decision                              | Choice                                                                 | Reason                                                                                              | Alternatives                                            | Tradeoffs                                                              |
| ------------------------------------- | ---------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------- |
| Aggregator `JobSource` vs CUSTOM endpoint | Model as aggregator `VISAJOBS` source (route through `AggregatorIngestionServiceImpl`) | Cross-source dedup (fingerprint enrich + filter-chain SKIP) lives only in the aggregator ingestion path; the CUSTOM `CrawlService` path would let re-aggregated jobs duplicate direct-board jobs | Reuse `AiPageStrategy` `json_api`+`headers` (CUSTOM path) | Slightly more code (new strategy) but dedup correctness is non-negotiable |
| New strategy vs `RestApiStrategy`/`AiPageStrategy` | New lightweight `VisaJobsStrategy` | Needs Bearer token rotation + PostgREST `offset` pagination + per-request auth headers; neither existing strategy models token refresh | Extend `RestApiStrategy` with header support | Avoids polluting a generic strategy with visajobs-specific auth |
| Token storage                          | `VISAJOBS_REFRESH_TOKEN` env var read by `VisaJobsTokenProvider` via `@Value` | Secret never enters source control, backups, or the per-source `config` map | Store refresh token in `config` map or DB `ats_slug` | Env-var indirection is the PLAN-preferred option; anon key stays public in YAML |
| Per-request Bearer header              | `.header("Authorization", "Bearer " + token)` per request               | Shared WebClient bean — a global `defaultHeader` would leak/race the token across sources | `WebClient` global `defaultHeader`                    | Slightly more boilerplate; correct isolation (verified Spring WebClient defaultHeader is global) |
| 401 handling                           | Invalidate token → refresh → retry **once**; then `PROTECTED`            | Access token TTL ~1h; single transparent retry is enough; avoids hammering on a bad token | Unlimited retry loop / fail silently                 | Distinct `PROTECTED` status surfaces re-login instead of silent empty  |
| Revoked refresh token                  | `VisaJobsRefreshTokenException` → `FetchResult.protectedEndpoint`        | Supabase can revoke refresh tokens (logout-all, password change)                                   | Return `FetchResult.error`                             | `PROTECTED` is the existing "needs auth" signal; manual re-login is rare |
| `visa_confirmed=eq.Yes` filter         | Server-side filter by default, `visa-confirmed-only` config toggles      | Cuts 36k→5.6k rows; focuses on the source's unique value (curated visa-confirmed overlay)          | Ingest all rows                                        | Configurable so full-table ingest remains possible                     |
| Visa mapping (`visa-exempt: true`)     | Skip visa filter; `visaSponsorship = UNKNOWN` (no badge)                 | Source is visa-sponsored by definition; current product suppresses badges for visa-exempt/DE jobs    | `visa-exempt: false` (description-based detection)     | Detection on description is unreliable; `UNKNOWN` is honest + consistent |
| `visa_confirmed` annotation (deferred) | Preserve `visa_confirmed`/`visa_type` in `rawJson`; defer a `VisaSponsorship.CONFIRMED` enricher | Surfacing a badge is optional and conflicts with the visa-exempt badge-suppression behavior         | Add a `VisaConfirmationEnricher` now                   | Deferred enricher is low-risk, future-proof; no schema/filter change now |
| Insert vs enrich                       | Both — let existing fingerprint dedup decide                              | Rows matching an ATS/direct job **enrich** (add `externalLinks`); unmatched rows **insert** as new VISAJOBS jobs | Insert-only or enrich-only                            | Maximizes the source's value (visa overlay) without surfacing duplicates |
| `externalId` derivation                | `job_id` verbatim if ≤255, else SHA-256 hex                               | `job_id` is stable + unique; `external_id` is VARCHAR(255); synthetic keys like `arb_...` can exceed 255 | Always hash `job_id`                                   | Verbatim keys are human-readable/joinable; hash only when necessary     |
| `salary` left null                     | Do not map single `salary` string to `salaryMin/Max`                      | Unknown free-text format; mapping risks fabricating numbers                                            | Parse numeric salary when present                       | Honest null; `rawJson` retains the raw value for future scoring         |
| Ordering vs concurrency                | Order-tolerant dedup (enrich + `demoteAggregatorDupes`); no scheduler change | `PipelineScheduler` runs crawl + aggregators concurrently; strict ordering is not enforceable        | Add scheduler phase/ordering                          | Self-convergent across ticks; avoids touching the scheduler             |

## Risks

| Risk                                        | Impact                                      | Likelihood | Mitigation                                                              |
| ------------------------------------------- | ------------------------------------------- | ---------- | ----------------------------------------------------------------------- |
| Cross-source duplicates surface on dashboard | Same job appears from visajobs + direct board | Medium     | Fingerprint enrichment (adds `externalLinks`) + filter-chain SKIP + `demoteAggregatorDupes`; verify in Phase 2 |
| Ordering/supersede race (first tick)       | visajobs copy inserted before direct board  | Medium     | Direct board later supersedes via `demoteAggregatorDupes`; visajobs rows carry full `description_text` (not thin) |
| Access token expiry (~1h) mid-crawl        | 401 on a page                                | High       | 401 → refresh → retry once; token provider caches + refreshes proactively |
| Refresh token revocation                   | Permanent 401 → source stops                 | Low        | `VisaJobsRefreshTokenException` → `PROTECTED` (re-login signal); manual re-capture rare |
| RLS policy change (anon-readable / paid tier) | Auth model changes                         | Low        | Monitor 200-vs-401; anon-key-only probe (`[]` vs rows) is a cheap health check |
| Secret exposure                             | Refresh token leaks via source control/backup | Low        | Env-var indirection only; anon key is public (no concern)               |
| `job_id` exceeds VARCHAR(255)               | DB constraint violation                     | Low        | SHA-256 `deriveExternalId` fallback                                     |
| PostgREST `limit` rejected/truncated        | Silent truncation / short pages             | Medium     | Default `limit: 100`; verify Supabase `max-rows` at impl; offset pagination terminates on short page |
| ToS / anti-bot on members-only board        | Access blocked                              | Medium     | Bounded page size, polite delay, no hammering; explicit user go-ahead (PLAN) |
| Offset pagination drift (rows inserted mid-crawl) | Duplicate or skipped rows              | Low        | Dedup by `job_id`; stable `order=posted.desc,job_id.desc`               |

## Test Plan

### Unit Tests

#### `VisaJobsStrategyTest` (WireMock-stubbed Supabase REST)

| Scenario                                     | Setup                                                                      | Assertion                                                              |
| -------------------------------------------- | -------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Happy path — single page                     | WireMock returns 3 rows                                                    | `FetchResult.success` with 3 jobs; correct field mapping               |
| Headers sent                                 | Capture request on WireMock                                                | `apikey` + `Authorization: Bearer <token>` present                     |
| Pagination via offset                        | 2 pages (page 1 = `limit` rows, page 2 = short page)                       | Both pages fetched; offsets 0 then `limit`; stops on short page         |
| `max-results` cap                            | API has 1000 rows, `max-results=50`                                        | Returns ≤50; no further pages                                          |
| `visa-confirmed-only=true` adds filter       | Capture request URL                                                        | URL contains `visa_confirmed=eq.Yes`                                   |
| `visa-confirmed-only=false` omits filter     | Config `false`                                                             | URL has no `visa_confirmed` param                                      |
| Dedup by `job_id`                            | Two rows share `job_id`                                                    | Each job returned once                                                 |
| `externalId` >255 chars                      | `job_id` 300+ chars                                                        | `externalId` is 64-char SHA-256 hex                                    |
| Null `title`/`url`/`job_id`                  | Rows missing those fields                                                  | Row skipped, others returned                                           |
| 401 then refresh+retry succeeds              | First GET 401, second GET (after refresh) 200                              | Refresh endpoint called once; final result `success`                    |
| 401 then refresh fails                       | First GET 401, refresh POST 400                                            | `FetchResult.protectedEndpoint` (PROTECTED)                            |
| Revoked refresh token                        | Refresh POST 401/400 (invalid grant)                                       | `VisaJobsRefreshTokenException` surfaced as `PROTECTED`                 |
| Rate limit (429)                             | WireMock 429                                                               | `FetchResult.rateLimited` (or error after filter retries)              |
| Malformed JSON                               | Invalid body                                                               | `FetchResult.error` with parse error                                    |
| Empty array                                  | `[]`                                                                       | `FetchResult.empty`                                                     |
| Late-page failure retains partial            | Page 1 ok, page 2 500                                                      | Partial jobs from page 1 returned                                       |
| `posted` parse failure                       | `posted` non-ISO                                                            | `postedDate` null, job still returned                                   |
| `delay-between-pages-ms` honored            | 2 pages, delay 400                                                         | ≥400ms between page requests                                            |

#### `VisaJobsTokenProviderTest`

| Scenario                                     | Setup                                                                      | Assertion                                                              |
| -------------------------------------------- | -------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Lazy obtain on first call                    | No cached token                                                            | Refresh endpoint called; access token returned                         |
| Cached token reused within TTL               | Cached token not near expiry                                                | No refresh call; same token returned                                   |
| Refresh on near-expiry                       | Cached token within 5-min expiry margin                                    | Refresh called; new token returned                                     |
| `invalidate()` forces refresh                | Valid cached token, then `invalidate()`                                    | Next call refreshes                                                    |
| Refresh failure                              | Refresh POST 400/401                                                        | Throws `VisaJobsRefreshTokenException`                                 |
| Missing env token                            | `VISAJOBS_REFRESH_TOKEN` unset                                              | Provider fails fast (no silent empty)                                  |

### Integration Tests

- `DynamicSourceConfigLoader` wiring: YAML entry → `SourceConfig` with `strategy.name()=="visajobs"`, `JobSource.valueOf("VISAJOBS")`, `DiscoverySource.valueOf("VISAJOBS")`, `visaExempt()==true`.
- `AggregatorIngestionServiceImpl` dedup: a `RawAggregatorJob` whose fingerprint matches an existing ATS job → `enriched++` (externalLinks updated) not `created++`; a fresh job → `created++` with `dedupHash` populated from `applyUrl`.
- `VisaJobsTokenProvider` ↔ `VisaJobsStrategy` 401-refresh-retry handshake against WireMock.

### End-to-End Verification

- After deploy: `curl -X POST localhost:8089/api/admin/crawl` triggers the pipeline (aggregator sources included).
- Verify persistence: `SELECT count(*) FROM job_posting WHERE source = 'VISAJOBS'` > 0.
- Verify dedup: a job already ingested from Arbeitnow/Workable does **not** produce a second active KEEP row; the ATS/direct row gains a `VISAJOBS` entry in `external_links`.
- Verify auth: after setting an invalid `VISAJOBS_REFRESH_TOKEN`, the source run records `PROTECTED` status (not silent empty); after restoring it, the next run recovers.
- Health endpoint shows visajobs source status.

### Non-Functional

| Concern     | Requirement                                                                        |
| ----------- | ---------------------------------------------------------------------------------- |
| Performance | Bounded fetch: ≤ `max-results` jobs; 5.6k visa rows ≈ 56 pages at `limit=100`       |
| Resilience  | Single page failure doesn't abort the fetch (partial retained); 401 self-heals via refresh |
| Memory      | ≤ `max-results` × ~2KB JSON ≈ a few MB peak; no concern                             |
| Idempotency | Re-running produces same results (L1 by `externalId`, fingerprint enrich vs insert) |
| Politeness  | `delay-between-pages-ms` + `RetryableWebClientFilter` respect remote throttling     |
| Security    | Refresh token via env only; anon key public; per-request Bearer (no global leakage) |

## File Change List

| File                                                                                 | Change Type | Description                                                              |
| ------------------------------------------------------------------------------------ | ----------- | ------------------------------------------------------------------------ |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/VisaJobsStrategy.java`          | **New**     | `FetchStrategy` impl (pagination, auth headers, mapping, 401-refresh)    |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/VisaJobsTokenProvider.java`     | **New**     | Access-token holder: refresh via `/auth/v1/token`, cache, `invalidate`, revoked-token exception |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/VisaJobsRefreshTokenException.java` | **New**  | Checked/runtime exception signaling revoked refresh token                 |
| `api/src/main/java/dev/jobhunter/model/enums/JobSource.java`                         | Modify      | Add `VISAJOBS` to enum + `AGGREGATORS` list                              |
| `api/src/main/java/dev/jobhunter/model/enums/DiscoverySource.java`                   | Modify      | Add `VISAJOBS` to enum                                                   |
| `api/src/main/resources/application.yaml`                                            | Modify      | Add `visajobs` source entry under `aggregator.sources`                   |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/VisaJobsStrategyTest.java`      | **New**     | Unit tests with WireMock (pagination, auth, 401-refresh, mapping)        |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/VisaJobsTokenProviderTest.java` | **New**     | Unit tests for cache/refresh/invalidate/revocation                        |

> **Not modified:** `YamlSourceConfig.java` (strategy reads its keys directly from `context.config()`), `AggregatorIngestionServiceImpl.java`, `CrawlService.java`, filter chain, scoring, and all dedup infrastructure — the design reuses the existing L1/L5/fingerprint/`demoteAggregatorDupes` layers without change.

> **Optional, deferred (not in this change):** a `VisaConfirmationEnricher` (PostIngestionEnricher) that reads `visa_confirmed`/`visa_type` from `rawJson` and annotates `visaSponsorship=CONFIRMED` for `VISAJOBS` rows, if the product later wants the badge. Out of scope for the initial integration because the visa-exempt path deliberately suppresses the badge.
