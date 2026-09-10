# HLD: GlobalMove (The Global Move) Authenticated Aggregator Source

## Tech Stack

| Category  | Technology            | Purpose                                                                  |
| --------- | --------------------- | ------------------------------------------------------------------------ |
| Language  | Java 21               | Matches existing API codebase                                            |
| Framework | Spring Boot 3.3.5     | DI, component scan, shared WebClient bean                                |
| HTTP      | WebClient (JDK HttpClient) | Non-blocking GET to `/jobs?page=N` + `/jobs/counts`; follows redirects (`Redirect.NORMAL`) |
| HTML/JSON | Jackson ObjectMapper + Jsoup | Extract embedded Inertia JSON from `<script data-page="app" type="application/json">`, then parse `props.jobs` paginator |
| Auth      | Laravel magic-link session | `Cookie: the-global-move-session=…` (httponly, sliding 2h); secret held in env, never YAML/DB |
| Config    | Spring Boot YAML      | Source config via `AggregatorSourceProperties`; session cookie via env vars |
| Scheduler | Quartz               | `GlobalMoveKeepaliveJob` pings every ≤90 min to keep the sliding session alive |
| Testing   | JUnit 5 + WireMock    | Stub Inertia HTML + auth responses; verify pagination/posted_date/auth/keepalive |

## Components

| Component | Responsibility | Dependencies |
| --------- | -------------- | ------------ |
| `scripts/globalmove-login.sh` | One-time capture helper: CSRF dance → `POST /magic-link {email}` → paste magic-link URL → exchange → print the two cookie values | curl, jq/openssl (XSRF decode), terminal |
| `GlobalMoveSessionManager` (@Component) | Hold session cookie from env (`GLOBALMOVE_SESSION_COOKIE`, `GLOBALMOVE_XSRF_TOKEN`); track state (UNCONFIGURED/VALID/DEAD); `ping()` replays cookie, reads re-issued `Set-Cookie`, rotates in-memory value; `isConfigured()` / `isValid()` / `markDead()` | WebClient, `@Value` env vars |
| `GlobalMoveKeepaliveJob` (Quartz `Job`) | Thin wrapper: calls `sessionManager.ping()` on `globalmove.keepalive.schedule`; no-ops when not configured | GlobalMoveSessionManager |
| `GlobalMoveStrategy` (@Component, `FetchStrategy`, name=`globalmove`) | Fetch `GET {url}?page=N` with session cookie, parse embedded Inertia JSON, paginate via `next_page_url`, map items → `RawAggregatorJob`, signal re-login on dead session | WebClient, GlobalMoveSessionManager, ObjectMapper |
| `GlobalMoveSessionException` | Runtime exception signaling dead/missing session (analogous to `VisaJobsRefreshTokenException`) | None |
| `JobSource` (mod) | New `GLOBAL_MOVE` enum value + add to `AGGREGATORS` list | None |
| `DiscoverySource` (mod) | New `GLOBAL_MOVE` enum value | None |
| `QuartzConfig` (mod) | Register `GlobalMoveKeepaliveJob` job detail + trigger (top-level `globalmove.keepalive.schedule`) | GlobalMoveKeepaliveJob |
| `application.yaml` (mod) | `aggregator.sources[]` entry + top-level `globalmove.keepalive.schedule` | AggregatorSourceProperties |
| `AggregatorIngestionServiceImpl` (existing) | Routing path providing L1 `source+externalId` skip, L5 `apply_url` `dedupHash`, fingerprint enrichment, filter chain | JobPostingRepository, JobFilterChain |
| `DynamicSourceConfigLoader` (existing) | YAML → `SourceConfig`; resolves `strategy: globalmove` via `StrategyRegistry`; injects `url` into `config` map | AggregatorSourceProperties, StrategyRegistry |

> **No `YamlSourceConfig` change**: like `VisaJobsStrategy`/`WorkInFinlandStrategy`, `GlobalMoveStrategy` reads `url` and `delay-between-pages-ms` directly from `context.config()`.

## Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PipelineScheduler (existing)                    │
│   [direct-board crawl ∥ aggregator sources — CONCURRENT, no order]     │
└───────────────────────┬────────────────────────────┬───────────────────┘
                        │                            │
                        ▼ (async)                    ▼ (async)
┌──────────────────────────────┐    ┌────────────────────────────────────────┐
│ CrawlService (direct ATS)    │    │ AggregatorIngestionServiceImpl          │
│  ATS/CUSTOM endpoints        │    │  ingest(globalmoveSource)               │
└──────────────────────────────┘    └───────────────┬────────────────────────┘
                                                     │ source.strategy().fetch(ctx)
                                                     ▼
                        ┌─────────────────────────────────────────────────────┐
                        │              GlobalMoveStrategy                      │
                        │  (implements FetchStrategy, name="globalmove",       │
                        │   supportedTypes={})                                 │
                        └───────┬──────────────────────────────┬──────────────┘
                                │                              │
              ┌─────────────────┘                              │
              ▼                                                ▼
 ┌──────────────────────────────────┐        ┌──────────────────────────────────────┐
 │ GlobalMoveSessionManager          │        │ GET {url}?page=N                     │
 │  isValid()? else throw            │        │  Cookie: the-global-move-session=…   │
 │    GlobalMoveSessionException     │        │  (session cookie only; no XSRF)      │
 │  (holds env cookies, state)       │        │  → 200 HTML with embedded            │
 │                                   │        │    <script data-page="app"           │
 │                                   │        │    type="application/json">          │
 │                                   │        │    props.jobs = Laravel paginator    │
 │                                   │        │    { data[20], next_page_url }       │
 └──────────────┬────────────────────┘        └──────────────┬───────────────────────┘
                │ Cookie value                               │ page HTML
                └────────────────────────────────────────────┘
                                                │ parse <script> JSON → props.jobs
                                                │ paginate while next_page_url != null
                                                │ && count < maxResults (delay 800ms)
                                                ▼
                                ┌──────────────────────────────────────┐
                                │ Map item → RawAggregatorJob           │
                                │  externalId ← gm-{id}                 │
                                │  title ← name                         │
                                │  company ← company                    │
                                │  location ← countries[] joined        │
                                │  description ← null (none available)  │
                                │  applyUrl ← apply_url (L5-critical)   │
                                │  postedDate ← parse relative string   │
                                │  extra metadata → rawJson (carried)   │
                                └───────────────┬──────────────────────┘
                                                ▼ FetchResult.success(jobs)
                                ┌──────────────────────────────────────┐
                                │ Dedup layers (existing)               │
                                │  L1 source+externalId (gm-{id})       │
                                │  L5 dedupHash (cross-source, applyUrl) │
                                │  fingerprint enrich (ATS/direct)      │
                                │  filter-chain fingerprint SKIP        │
                                └───────────────┬──────────────────────┘
                                                ▼
                                ┌──────────────────────────────────────┐
                                │ Filter chain (visa-exempt=true →      │
                                │  visaSponsorship=UNKNOWN)             │
                                │  → persist JobPosting (GLOBAL_MOVE)   │
                                └──────────────────────────────────────┘

         (out-of-band, one-time)                 (every ≤90 min, Quartz)
 ┌───────────────────────────────┐      ┌───────────────────────────────────────┐
 │ scripts/globalmove-login.sh   │      │ GlobalMoveKeepaliveJob                 │
 │  → env GLOBALMOVE_SESSION_*   │      │  → sessionManager.ping()               │
 └───────────────────────────────┘      │    GET /jobs/counts (cookie)           │
                                        │    read Set-Cookie → rotate in-memory   │
                                        │    (keeps sliding 2h session alive)     │
                                        └───────────────────────────────────────┘
```

**Description:** `GlobalMoveStrategy` is a `@Component` implementing `FetchStrategy`. It reads `url` (the `/jobs` list page, injected as `config.url` by `DynamicSourceConfigLoader`) and `delay-between-pages-ms` from `context.config()`. Before fetching it asks `GlobalMoveSessionManager` for the current session cookie (from env, rotated in-memory by keepalive). It issues `GET {url}?page=N` with a `Cookie: the-global-move-session=…` header **per request** (never a global WebClient default header, to avoid leaking the subscriber cookie to other sources). The response is a server-rendered Inertia page; the strategy extracts the embedded JSON from `<script data-page="app" type="application/json">` and reads `props.jobs` (a Laravel paginator: `data[]` of 20 items + `next_page_url`). It walks pages until `next_page_url` is null or `maxResults` is reached, with a polite delay between pages. Items are mapped to `RawAggregatorJob` with `applyUrl = apply_url` (the original board URL, which drives L5 `dedupHash` and cross-source fingerprint enrichment). Downstream dedup, filter chain, scoring, and persistence are unchanged.

**Concurrency note (ordering):** `PipelineScheduler` runs the direct-board crawl and all aggregator sources **concurrently** (no guaranteed order). The design is order-tolerant, mirroring VisaJobs: on ticks where a direct-board job already exists, the fingerprint enrichment path adds an `externalLinks` entry rather than inserting; if a GlobalMove row inserts first, a later direct-board crawl supersedes it via `CrawlService.demoteAggregatorDupes`. No scheduler change.

**Session lifecycle state machine:**

```
                    env cookies absent/blank
        ┌──────────────┐
        │ UNCONFIGURED │
        └──────┬───────┘
               │ operator runs globalmove-login.sh,
               │ sets env GLOBALMOVE_SESSION_COOKIE,
               │ restarts API (SessionManager reads env)
               ▼
        ┌──────────────┐   ping success (Set-Cookie rotated)   ┌──────────────┐
        │    VALID     │ ─────────────────────────────────────► │    VALID     │ (sliding 2h)
        └──────┬───────┘                                        └──────────────┘
               │ ping/fetch detects login page, 401/403, or
               │ server-side invalidation (logout-all / lapse)
               ▼
        ┌──────────────┐
        │     DEAD     │ ──► fetch() → GlobalMoveSessionException → FetchResult.protectedEndpoint (PROTECTED)
        └──────┬───────┘
               │ manual re-capture (rare) + restart
               ▼
             VALID
```

- **UNCONFIGURED** — `GLOBALMOVE_SESSION_COOKIE` unset/blank. Keepalive no-ops; strategy returns `PROTECTED` (never a silent empty result).
- **VALID** — cookie present and last ping/fetch succeeded. Keepalive ping rotates the in-memory cookie value from re-issued `Set-Cookie` headers (the session ID is typically stable; the re-issue refreshes the 2h expiry).
- **DEAD** — a ping or fetch observed an auth failure. Strategy surfaces `PROTECTED`; recovery is a manual re-capture + restart.

## Interfaces

### FetchStrategy (existing — implemented by GlobalMoveStrategy)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `name()` | none | `String` | Returns `"globalmove"` | None |
| `supportedTypes()` | none | `Set<AtsType>` | Empty set (aggregator-only) | None |
| `fetch(FetchContext)` | FetchContext | `FetchResult` | Validate config, resolve cookie, paginate, map | `FetchResult.error` / `rateLimited` / `protectedEndpoint` |

### GlobalMoveSessionManager (new `@Component`)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `isConfigured()` | none | `boolean` | True when `GLOBALMOVE_SESSION_COOKIE` is non-blank | None |
| `isValid()` | none | `boolean` | True when configured and state != DEAD | None |
| `getSessionCookie()` | none | `String` | Return current in-memory cookie value (env value until first rotation) | `GlobalMoveSessionException` if unconfigured/dead |
| `ping()` | none | `void` | `GET /jobs/counts` with cookie; on 2xx read re-issued `Set-Cookie` and rotate; on auth failure `markDead()` | None (records state internally) |
| `markDead()` | none | `void` | Transition state to DEAD | None |

**Cookie handling:** only `the-global-move-session` is sent on GETs (fetch + ping). `GLOBALMOVE_XSRF_TOKEN` is captured/stored for completeness but is only needed for state-changing POSTs (a non-goal) and is never sent by the strategy or keepalive. Rotation of the re-issued cookie requires reading response `Set-Cookie`, so `ping()` uses `.exchangeToMono()` (not `.retrieve()`) to access `ClientResponse.cookies()`.

### GlobalMoveStrategy (internal methods)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `fetch` | FetchContext | FetchResult | Validate config + session, paginate `/jobs?page=N`, map, return success/error | error/rateLimited/protectedEndpoint |
| `fetchPage` | `String url, int page` | `JsonNode` (paginator) | GET one page with session cookie; parse embedded `<script data-page="app">` JSON → `props.jobs` | WebClientResponseException, parse error |
| `mapJob` | JsonNode item | RawAggregatorJob | Map item per field-mapping table; skip if no `id`/`name`/`apply_url` | null (skip) |
| `parsePostedDate` | JsonNode | LocalDate | `"NEW"` → today; `"Nd ago"` → today−N; null on failure | null |
| `parseLongConfig` | key, default | long | Parse `context.config()` string value | Fall back to default |
| `deriveExternalId` | String id | String | `"gm-" + id` | None |

### GlobalMoveKeepaliveJob (new Quartz `Job`)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `execute` | JobExecutionContext | void | No-op if not configured; else `sessionManager.ping()`; log DEAD on failure | None (swallowed + logged) |

### scripts/globalmove-login.sh (capture helper)

| Step | Action |
| ---- | ------ |
| 1 | `GET /login` with cookie jar → decode `XSRF-TOKEN` cookie value |
| 2 | `POST /magic-link {email}` with `X-XSRF-TOKEN` header (email from env/arg) |
| 3 | Prompt user to paste the received magic-link URL |
| 4 | `GET /magic-link/authenticate?email&expires&signature` with cookie jar |
| 5 | Print `the-global-move-session` and `XSRF-TOKEN` values for pasting into env |

## Data Flow

| Step | Component | Action | Next |
| ---- | --------- | ------ | ---- |
| 1 | PipelineScheduler | Runs direct-board crawl + aggregator sources (concurrent) | AggregatorIngestionServiceImpl |
| 2 | AggregatorIngestionServiceImpl | `source.strategy().fetch(source.buildContext())` | GlobalMoveStrategy |
| 3 | GlobalMoveStrategy | Read `url`, `delay-between-pages-ms`; check `sessionManager.isValid()` | GlobalMoveSessionManager |
| 4 | GlobalMoveSessionManager | Return session cookie (throws `GlobalMoveSessionException` if unconfigured/dead) | GlobalMoveStrategy |
| 5 | GlobalMoveStrategy | `GET {url}?page=1` with `Cookie` header; parse embedded Inertia JSON → `props.jobs` | Self (pagination loop) |
| 6 | GlobalMoveStrategy | While `next_page_url != null` && count < `maxResults`: fetch `?page=N`, sleep `delay-between-pages-ms` | Mapping |
| 7 | GlobalMoveStrategy | Map each `data[]` item → `RawAggregatorJob` (applyUrl=apply_url, description=null) | FetchResult |
| 8 | GlobalMoveStrategy | `FetchResult.success(jobs, elapsed)` | AggregatorIngestionServiceImpl |
| 9 | AggregatorIngestionServiceImpl | L1 externalId → L5 dedupHash → fingerprint enrich → filter-chain SKIP | Persistence |
| 10 | Filter chain | visa-exempt=true → visaSponsorship=UNKNOWN; role/location/yoe/dedup | Persistence |
| 11 | Persistence | Insert `JobPosting` (source=GLOBAL_MOVE, dedupHash set, fingerprint set) | Post-ingestion scoring |
| — | GlobalMoveKeepaliveJob (out-of-band) | `GET /jobs/counts` with cookie; rotate `Set-Cookie` | Self (every ≤90 min) |

**Error Flows:**
- Missing `url` config → `FetchResult.error("GlobalMove config requires url", elapsed)`.
- Unconfigured session (`GLOBALMOVE_SESSION_COOKIE` unset) → `GlobalMoveSessionException` → `FetchResult.protectedEndpoint(elapsed)` (PROTECTED), never silent empty.
- Dead session (keepalive marked DEAD, or fetch hit login page / 401 / 403) → `GlobalMoveSessionException` → `PROTECTED` (distinct re-login signal).
- Redirect-to-login: the shared WebClient follows redirects (`HttpClient.Redirect.NORMAL`), so a 302→`/login` is transparent; detection is content-based (login payload has no `props.jobs` paginator).
- Structural change / Inertia version bump (jobs route but `props.jobs` missing or malformed) → `FetchResult.error("GlobalMove parse failed …")` (not silent empty; detectable in health report).
- HTTP 429 → retried by `RetryableWebClientFilter` (3× backoff); if exhausted → `FetchResult.rateLimited(elapsed)`.
- HTTP 5xx → retried once by filter; if exhausted → `FetchResult.error(...)`.
- Late-page failure after partial success → retain already-collected jobs, return `FetchResult.success(partial)` (best-effort, mirrors `WorkInFinlandStrategy`).
- `posted_date` unparseable → `postedDate` null; job still returned (dedup does not depend on it — uses `apply_url` hash + fingerprint).

## Data Model

No new entities. Mapping from GlobalMove list item → existing `RawAggregatorJob` record → `JobPosting`:

### Field Mapping: GlobalMove item → RawAggregatorJob → JobPosting

| GlobalMove field | RawAggregatorJob field | JobPosting field | Transform |
| ---------------- | ---------------------- | ---------------- | --------- |
| `id` | `externalId` | `external_id` | `"gm-" + id` (stable, source-scoped; drives L1 skip) |
| `name` | `title` | `title` | Direct string |
| `company` | `companyName` | `company` (Company) | `resolveCompany()` (normalized lookup/insert) |
| `countries[]` | `location` | `location`, `location_city`, `location_country` | Join array (e.g. `", "`); `LocationCountryParser.extractCity`; filter-chain `countryIso` |
| *(none — board has no description)* | `description` (null) | `description` (null) | No detail endpoint; scoring runs on title/company/location |
| `apply_url` | `applyUrl` | `apply_url`, `dedup_hash`, `external_links` | Direct string — original board URL; `DedupHashUtil.compute` → L5 `dedupHash`; enrichment adds `externalLinks[source]` |
| `posted_date` | `postedDate` | `posted_date` | `"NEW"` → today; `"Nd ago"` → today−N; null on parse failure |
| *(none)* | `salaryMin/Max/Currency` (null) | `salary_min/max/currency` (null) | Not available on list item |
| `categories[], work_mode, remote_types[], company_size, industry, linkedin_url, keywords[], company_color, status` | `rawJson` (carried only) | *(not persisted today)* | `node.toString()` — see note below |

> **Extra metadata is carried, not persisted.** `RawAggregatorJob` has no `tags`/`externalLinks` field (its only unstructured field is `rawJson`), and the current `AggregatorIngestionServiceImpl` does **not** copy `rawJson` into `JobPosting.rawContent`. Therefore `categories`, `work_mode`, `company_size`, `industry`, `linkedin_url`, `keywords`, `remote_types`, `status` are carried through `rawJson` but dropped at persistence time today. They remain available in `rawJson` for a future `PostIngestionEnricher` (e.g. persist `linkedin_url` to `externalLinks` or `categories`/`work_mode` to `rawContent`) — deferred, not in this change.

## Config Schema

### YAML entry in `application.yaml` under `aggregator.sources`

```yaml
- name: globalmove
  strategy: globalmove
  job-source: GLOBAL_MOVE
  discovery-source: GLOBAL_MOVE
  url: "https://globalmove.relocate.me/jobs"
  frequency-hours: 12        # informational only
  max-results: 300           # cap across all pages (bounded by context.maxResults())
  visa-exempt: true          # board is already visa-filtered
  config:
    delay-between-pages-ms: "800"
```

### Top-level keepalive schedule (in `application.yaml`)

```yaml
globalmove:
  keepalive-schedule: "0 0 * * * ?"   # hourly — Quartz rejects increments >60 (0/90 is invalid)
```

The **session secret is NOT in YAML/DB** — it is read from environment variables `GLOBALMOVE_SESSION_COOKIE` (and `GLOBALMOVE_XSRF_TOKEN`) by `GlobalMoveSessionManager` via `@Value("${GLOBALMOVE_SESSION_COOKIE:}")`, sourced like `VISAJOBS_REFRESH_TOKEN` via `~/.zshenv`.

### Config key semantics

| Key | Type | Required | Default | Description |
| --- | ---- | -------- | ------- | ----------- |
| `url` (top-level) | String | Yes | — | `/jobs` list page (injected by `DynamicSourceConfigLoader` as `config.url`) |
| `config.delay-between-pages-ms` | String | No | `0` | Polite delay between `?page=N` requests (Cloudflare courtesy) |
| `GLOBALMOVE_SESSION_COOKIE` | env | Yes | — | httponly Laravel session cookie value (secret; never committed) |
| `GLOBALMOVE_XSRF_TOKEN` | env | No | — | XSRF token (captured for completeness; not sent on GETs) |
| `max-results` | int | No | `50` | Total job cap across all pages (`context.maxResults()`) |
| `visa-exempt` | bool | No | `false` | `true` → skips visa filter; `visaSponsorship` stays `UNKNOWN` |
| `frequency-hours` | int | No | `12` | Informational only |
| `globalmove.keepalive-schedule` (top-level) | String | No | `0 0 * * * ?` | Quartz cron for keepalive ping |

> **Cron semantics note (corrected at impl):** Quartz cron rejects increments > 60 (`0/90` throws `ParseException: Increment > 60`), so the keepalive uses **hourly** `0 0 * * * ?` — a 60-min cadence that satisfies the ≤90-min requirement with 2× margin against the 2h sliding session. The keepalive job no-ops when the cookie is unset.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
| -------- | ------ | ------ | ------------ | --------- |
| Aggregator `JobSource` vs CUSTOM endpoint | Model as aggregator `GLOBAL_MOVE` source (route through `AggregatorIngestionServiceImpl`) | Cross-source dedup (L5 `apply_url` hash + fingerprint enrich) lives only in the aggregator path; the board re-aggregates boards JobHunter already crawls directly | Reuse `AiPageStrategy` (CUSTOM path) | Slightly more code but dedup correctness (no duplicate surface vs Greenhouse/LinkedIn) is non-negotiable |
| New strategy vs `RestApiStrategy`/`AiPageStrategy` | New `GlobalMoveStrategy` | Needs session-cookie auth, Inertia embedded-JSON extraction, `next_page_url` pagination, relative `posted_date` parsing — none of the existing strategies model these | Extend `RestApiStrategy` with cookie+HTML parsing | Avoids polluting a generic strategy with globalmove-specific auth/parsing |
| Session storage | `GLOBALMOVE_SESSION_COOKIE` env var read by `GlobalMoveSessionManager` | Secret never enters source control, backups, or the per-source `config` map | Store cookie in `config` map or DB | Env-var indirection is the VisaJobs-preferred option; capture is manual + rare |
| Per-request cookie header | `.cookie("the-global-move-session", …)` per request | Shared WebClient bean — a global `defaultHeader` would leak/race the subscriber cookie across sources | WebClient global `defaultHeader` | More boilerplate; correct isolation (verified Spring WebClient defaultHeader is global) |
| Keepalive ping endpoint | `GET /jobs/counts` | Cheap authenticated route; replays cookie so the sliding 2h session never lapses | `GET /jobs?page=1` (heavier) | `/jobs/counts` response shape is unverified — confirm at impl |
| Cookie rotation | `ping()` reads re-issued `Set-Cookie`, updates in-memory value | Sliding session re-issues cookies each response; in-memory rotation keeps the value fresh | Static env value (never rotate) | Requires `.exchangeToMono()` to read cookies; env snapshot stays valid (session ID typically stable, only expiry slides) |
| Re-login signal | `GlobalMoveSessionException` → `FetchResult.protectedEndpoint` (PROTECTED) | `PROTECTED` is the existing "requires authentication" signal (used by VisaJobs/SuccessFactors/Workday/StepStone); distinct from silent-empty | Return `FetchResult.error` / `empty` | Distinct signal; manual re-capture is rare |
| Auth-failure detection | Content-based (login payload has no `props.jobs`); 401/403 also → DEAD | Shared WebClient follows redirects, so 302→`/login` is invisible to the strategy | Non-redirect-following client to observe 302 | Content discriminator needs impl-time confirmation of the auth-page marker |
| `posted_date` parsing | Relative string → approximate `LocalDate`; null on failure | Board offers no absolute timestamp; dedup must not depend on it | Drop `postedDate` entirely | Approximate date improves scoring/timeline without risking dedup correctness |
| Extra metadata | Carry in `rawJson` only; do not persist | `RawAggregatorJob` has no tags/externalLinks; ingestion path doesn't persist `rawJson` | Add `PostIngestionEnricher` now | Honest scope; `linkedin_url`/`categories`/`work_mode` remain available for a future enricher |
| `externalId` prefix | `"gm-" + id` | Short, stable, human-readable; avoids cross-source externalId ambiguity | Raw `id` | Verbatim id is source-scoped by L1 anyway; prefix is hygiene |
| Ordering vs concurrency | Order-tolerant dedup (enrich + `demoteAggregatorDupes`); no scheduler change | `PipelineScheduler` runs crawl + aggregators concurrently; strict ordering is not enforceable | Add scheduler phase/ordering | Self-convergent across ticks (mirrors VisaJobs) |

## Risks

| Risk | Impact | Likelihood | Mitigation |
| ---- | ------ | ---------- | ---------- |
| Cross-source duplicates surface on dashboard | Same job appears from GlobalMove + direct board | Medium | L5 `dedupHash` on `apply_url` + fingerprint enrichment + `demoteAggregatorDupes`; verify in Phase 4 |
| Session server-side invalidation (logout-all, subscription lapse) | Dead session → source stops | Medium | Keepalive detects dead session → `PROTECTED` (clear re-login signal); re-capture manual but rare |
| Session lapses on app restart (env cookie superseded) | First fetch after restart fails | Low | Keepalive cadence (≤90 min) < sliding 2h window while app is up; on restart, stale env cookie → `PROTECTED` → re-capture |
| Magic links expire in minutes | Capture script window too short | Low | Script automates the trigger so the gap is seconds; script run promptly after triggering |
| Cloudflare blocking / anti-bot | Fetch rate-limited | Medium | Polite `delay-between-pages-ms` (800ms), ≤300 jobs ≈ ≤15 pages/run, realistic UA; robots.txt open |
| Inertia version bump / HTML change | `props.jobs` missing/malformed → parse error | Medium | Error result (not silent empty); structure change visible in health report |
| Relative `posted_date` inaccuracy | Approximate posted dates | Low | Dedup does not depend on it (apply_url hash + fingerprint) |
| `/jobs/counts` response shape differs from assumption | Keepalive ping misclassifies | Medium | Confirm at impl; ping success predicate = 2xx + non-login response |
| Cookie secret exposure | Subscriber session leaks via source control/backup | Low | Env-var indirection only; never YAML/DB/source control |
| Thin metadata (no descriptions) | Lower scoring signal | High | Accepted (board offers none); optional `aggregator.enrichment` backfill from `apply_url` later |
| Redirect-following masks 302→`/login` | Auth failure hard to detect via status | Medium | Content-based detection + `GlobalMoveSessionException`; flagged for impl-time confirmation |

## Test Plan

### Unit Tests

#### `GlobalMoveStrategyTest` (WireMock-stubbed Inertia HTML)

| Scenario | Setup | Assertion |
| -------- | ----- | --------- |
| Happy path — single page | WireMock returns Inertia HTML with `props.jobs.data` (3 items) + `next_page_url: null` | `FetchResult.success` with 3 jobs; correct field mapping |
| Session cookie sent | Capture request | `Cookie: the-global-move-session=…` present; no XSRF token |
| Pagination via `next_page_url` | Page 1 has `next_page_url`, page 2 null | Both pages fetched (`?page=1`, `?page=2`); stops on null |
| `max-results` cap | API has 1000 jobs, `max-results=300` | Returns ≤300; no further pages |
| `posted_date` "NEW" | Item `posted_date: "NEW"` | `postedDate == today` |
| `posted_date` "Nd ago" | Item `posted_date: "3d ago"` | `postedDate == today - 3` |
| `posted_date` unparseable | Item `posted_date: "garbage"` | `postedDate` null, job still returned |
| `externalId` prefix | Item `id: 1234` | `externalId == "gm-1234"` |
| Null `id`/`name`/`apply_url` | Items missing those fields | Row skipped, others returned |
| `countries[]` joined | `countries: ["Germany","Netherlands"]` | `location == "Germany, Netherlands"` |
| Login page (dead session) | WireMock returns login Inertia payload (no `props.jobs`) | `FetchResult.protectedEndpoint` (PROTECTED) |
| 401/403 | WireMock 401 | `FetchResult.protectedEndpoint` (PROTECTED) |
| Unconfigured session | `GLOBALMOVE_SESSION_COOKIE` unset | `GlobalMoveSessionException` → `PROTECTED`, never empty |
| Structural change (jobs route, no `props.jobs`) | WireMock returns jobs-looking HTML without `props.jobs` | `FetchResult.error` (parse error, not empty) |
| Rate limit (429) | WireMock 429 | `FetchResult.rateLimited` (or error after filter retries) |
| Malformed embedded JSON | Invalid `<script data-page="app">` JSON | `FetchResult.error` with parse error |
| Empty `data[]` | Paginator with `data: []` | `FetchResult.empty` |
| Late-page failure retains partial | Page 1 ok, page 2 500 | Partial jobs from page 1 returned |
| `delay-between-pages-ms` honored | 2 pages, delay 800 | ≥800ms between page requests |

#### `GlobalMoveSessionManagerTest`

| Scenario | Setup | Assertion |
| -------- | ----- | --------- |
| `isConfigured()` false when env unset | No env cookie | false; strategy path → PROTECTED |
| `getSessionCookie()` returns env value | Env cookie set | Returns stored value |
| `ping()` success rotates cookie | WireMock `/jobs/counts` 200 with fresh `Set-Cookie` | In-memory cookie updated to new value |
| `ping()` auth failure marks dead | WireMock returns login page / 401 | `isValid()` false; state DEAD |
| `markDead()` → `getSessionCookie()` throws | Valid then markDead | `GlobalMoveSessionException` |
| Keepalive no-op when unconfigured | No env cookie | `ping()` skipped, no HTTP call |

#### `GlobalMoveKeepaliveJobTest`

| Scenario | Setup | Assertion |
| -------- | ----- | --------- |
| No-op when unconfigured | `isConfigured()==false` | `sessionManager.ping()` never called |
| Pings when configured | `isConfigured()==true` | `ping()` called once |
| Ping failure logged, not thrown | `ping()` marks dead | `execute()` returns normally |

### Integration Tests

- `DynamicSourceConfigLoader` wiring: YAML entry → `SourceConfig` with `strategy.name()=="globalmove"`, `JobSource.valueOf("GLOBAL_MOVE")`, `DiscoverySource.valueOf("GLOBAL_MOVE")`, `visaExempt()==true`.
- `GlobalMoveSessionManager` ↔ `GlobalMoveStrategy` handshake against WireMock (valid cookie → success; dead cookie → `PROTECTED`).
- `AggregatorIngestionServiceImpl` dedup: a `RawAggregatorJob` whose fingerprint matches an existing ATS job → `enriched++` (externalLinks updated) not `created++`; a fresh job → `created++` with `dedupHash` populated from `apply_url`; a job whose `apply_url` hash collides with a known hash → `duplicates++`.

### End-to-End Verification

- After deploy: `curl -X POST localhost:8089/api/admin/aggregate/globalmove` returns >0 jobs.
- Verify persistence: `SELECT count(*) FROM job_posting WHERE source = 'GLOBAL_MOVE'` > 0.
- Verify dedup: a job already ingested from Greenhouse/LinkedIn does **not** produce a second active KEEP row; the ATS/direct row gains a `GLOBAL_MOVE` entry in `external_links`.
- Verify auth: after unsetting `GLOBALMOVE_SESSION_COOKIE`, the source run records `PROTECTED` status (not silent empty); after restoring it, the next run recovers.
- Verify keepalive: session stays valid across a >2h window (keepalive ping prevents lapse).
- Health endpoint (`/api/admin/aggregators`) shows `globalmove` source status.

### Non-Functional

| Concern | Requirement |
| ------- | ----------- |
| Performance | Bounded fetch: ≤ `max-results` (300) jobs ≈ ≤15 pages at 20/page + 800ms delay ≈ <15s/run |
| Resilience | Single page failure doesn't abort the fetch (partial retained); dead session surfaces `PROTECTED` (self-documenting) |
| Memory | ≤ `max-results` × ~2KB JSON ≈ a few MB peak; no concern |
| Idempotency | Re-running produces same results (L1 by `gm-{id}`, L5 by `apply_url` hash, fingerprint enrich vs insert) |
| Politeness | `delay-between-pages-ms` + `RetryableWebClientFilter` respect Cloudflare; single-digit page count per run |
| Security | Session cookie via env only; per-request Cookie header (no global leakage); XSRF token never sent on GETs |

## File Change List

| File | Change Type | Description |
| ---- | ----------- | ----------- |
| `scripts/globalmove-login.sh` | **New** | Capture helper: magic-link exchange → prints cookie values |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/GlobalMoveStrategy.java` | **New** | `FetchStrategy` impl (pagination, Inertia JSON extraction, mapping, auth signal) |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/GlobalMoveSessionManager.java` | **New** | Cookie holder: env read, `ping()`, rotation, state, `markDead()` |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/GlobalMoveSessionException.java` | **New** | Runtime exception signaling dead/missing session |
| `api/src/main/java/dev/jobhunter/scheduler/GlobalMoveKeepaliveJob.java` | **New** | Quartz job: `sessionManager.ping()` on schedule |
| `api/src/main/java/dev/jobhunter/config/QuartzConfig.java` | Modify | Register `GlobalMoveKeepaliveJob` job detail + trigger (`@Value("${globalmove.keepalive-schedule:0 0/90 * * * ?}")`) |
| `api/src/main/java/dev/jobhunter/model/enums/JobSource.java` | Modify | Add `GLOBAL_MOVE` to enum + `AGGREGATORS` list |
| `api/src/main/java/dev/jobhunter/model/enums/DiscoverySource.java` | Modify | Add `GLOBAL_MOVE` to enum |
| `api/src/main/resources/application.yaml` | Modify | Add `globalmove` source entry + top-level `globalmove.keepalive-schedule` |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/GlobalMoveStrategyTest.java` | **New** | Unit tests with WireMock |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/GlobalMoveSessionManagerTest.java` | **New** | Unit tests for cookie rotation/state/keepalive |
| `api/src/test/java/dev/jobhunter/scheduler/GlobalMoveKeepaliveJobTest.java` | **New** | Unit tests for no-op/ping/dead handling |

> **Not modified:** `YamlSourceConfig.java` (strategy reads keys directly from `context.config()`), `CrawlService.java`, filter chain, scoring, and all dedup infrastructure — the design reuses the existing L1/L5/fingerprint/`demoteAggregatorDupes` layers without change.
>
> **Post-impl change (approved):** L5 dedup was made **cross-source** — `AggregatorIngestionServiceImpl` now loads dedup hashes via the new `JobPostingRepository.findAllDedupHashes()` (all sources) instead of `findDedupHashesBySource` (source-scoped). Rationale: E2E surfaced 2 aggregator-vs-aggregator duplicates (identical `apply_url` vs Jobgether/BuiltInEurope) that source-scoped L5 + ATS-only fingerprint enrichment could not catch. Verified: re-crawl created 0 new rows; `careersingothenburg` now suppresses 97 cross-source dupes it previously inserted. New test: `AggregatorIngestionServiceImplTest.ingest_duplicateApplyUrlHashFromOtherSource_isDuplicate`.

> **Optional, deferred (not in this change):** a `PostIngestionEnricher` that persists `linkedin_url`, `categories`, `work_mode`, `company_size`, `industry` from `rawJson` into `JobPosting.externalLinks`/`rawContent`, and/or `aggregator.enrichment` description backfill from `apply_url`. Out of scope for the initial integration.
