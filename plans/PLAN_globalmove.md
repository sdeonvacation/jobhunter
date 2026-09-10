# Plan: GlobalMove (The Global Move) Authenticated Aggregator Source

## Overview

Ingest the subscriber-only job board `globalmove.relocate.me` as a new aggregator source.
Login is passwordless (magic link): a one-time user-assisted capture stores the Laravel
session cookies in env vars, and a keepalive ping every ≤90 min replays them so the sliding
2h session never lapses — making the login genuinely one-time. Jobs are fetched as Inertia
JSON, paginated, and routed through the existing aggregator ingestion pipeline (dedup via
`apply_url` against original boards). Modeled on the VisaJobs precedent
(`VisaJobsStrategy` + `VisaJobsTokenProvider`).

## Tech Stack

- Java 21, Spring Boot 3.3.5, WebClient, Jackson
- Existing `FetchStrategy` / `FetchContext` / `FetchResult` / `RawAggregatorJob`
- Existing `AggregatorIngestionServiceImpl` (L1/L5/fingerprint dedup)
- Existing Quartz scheduler for keepalive
- YAML source entry in `application.yaml` `aggregator.sources[]`
- No new dependencies

## Testing Strategy

- Unit: `GlobalMoveStrategyTest` — WireMock-stubbed Inertia HTML (embedded
  `<script data-page="app">` JSON), pagination walk, posted_date parsing ("NEW", "1d ago"),
  401/redirect-to-login → re-login signal, cookie header presence
- Unit: `GlobalMoveSessionManagerTest` — cookie expiry math, keepalive ping, refresh-on-401
- Done when: manual crawl `POST /api/admin/aggregate/globalmove` returns >0 jobs; a job whose
  `apply_url` matches an existing Greenhouse/LinkedIn job is NOT duplicated; expired session
  surfaces a clear re-login error rather than empty result

## Phases

### Phase 1: One-time login capture (manual, script-assisted)

- Step 1: Add capture helper script (`scripts/globalmove-login.sh`): triggers
  `POST /magic-link` (CSRF dance: GET /login → decode XSRF cookie → header), prompts user to
  paste the magic-link URL, exchanges it (curl cookie jar), prints the two cookie values
- Step 2: User pastes cookie values into env: `GLOBALMOVE_SESSION_COOKIE`,
  `GLOBALMOVE_XSRF_TOKEN` (sourced like `VISAJOBS_REFRESH_TOKEN` via `~/.zshenv`)
- Step 3: Verify capture with a single authenticated `GET /jobs` probe

### Phase 2: Session manager + keepalive

- Step 1: `GlobalMoveSessionManager` (@Component): holds cookies from env, tracks last-ping
  time, exposes `isValid()` / `ping()`; ping = cheap `GET /jobs/counts` with stored cookie;
  response re-issues fresh cookies → update stored values in memory (and optionally persist)
- Step 2: Quartz `GlobalMoveKeepaliveJob`: ping every 90 min (config
  `globalmove.keepalive.schedule`), disabled when no cookie configured
- Step 3: On ping failure (redirect to /login) → mark session dead, surface re-login signal

### Phase 3: GlobalMoveStrategy

- Step 1: `strategy/aggregator/GlobalMoveStrategy implements FetchStrategy`, name `globalmove`
- Step 2: Fetch `GET {url}?page=N` with session cookies; parse embedded Inertia JSON
  (`<script data-page="app" type="application/json">` → `props.jobs.data[]`)
- Step 3: Map fields → `RawAggregatorJob`: externalId=`gm-{id}`, title=`name`,
  company=`company`, location=`countries[]` joined, applyUrl=`apply_url`, postedDate parsed
  from relative string (`"NEW"`→now, `"Nd ago"`→now−N days; null on parse failure),
  extra metadata (categories, work_mode, company_size, industry, linkedin_url) into
  externalLinks/tags as the existing RawAggregatorJob supports
- Step 4: Pagination: loop while `next_page_url` != null && count < maxResults; polite delay
  between pages (config `delay-between-pages-ms`)
- Step 5: Auth failures (302→/login, empty props) → `FetchResult.error(RE_LOGIN_NEEDED)`,
  never silent-empty

### Phase 4: Source wiring + config

- Step 1: Add `JobSource.GLOBAL_MOVE` + `DiscoverySource.GLOBAL_MOVE` enum values
- Step 2: YAML entry:
  ```yaml
  - name: globalmove
    strategy: globalmove
    job-source: GLOBAL_MOVE
    discovery-source: GLOBAL_MOVE
    url: "https://globalmove.relocate.me/jobs"
    frequency-hours: 12
    max-results: 300
    visa-exempt: true
    config:
      delay-between-pages-ms: "800"
      keepalive-schedule: "0 0 * * * ?"
  ```
- Step 3: Manual crawl + dashboard verification (dedup, no duplicates vs Greenhouse/LinkedIn
  sources)

## Risks/Edge cases

- **Magic links expire in minutes**: capture script must be run promptly after triggering;
  script automates the trigger so the gap is seconds
- **Session invalidation server-side** (logout-all, subscription lapse): keepalive detects
  dead session → clear re-login signal; re-capture is manual but rare
- **No descriptions**: thin metadata only; scoring relies on title/company/location.
  Optionally run existing `aggregator.enrichment` to backfill from apply_url later
- **Relative posted_date**: approximate only; dedup must not depend on it (uses apply_url
  hash + fingerprint)
- **Cloudflare blocking**: polite delays, single-digit page count per run, realistic UA;
  robots.txt is open
- **Cookie secret exposure**: env vars only, never YAML/DB/source control
- **Inertia version bump / HTML change**: parse failure → error result, not silent empty;
  structure change is detectable in health report
- **Duplicate surface risk**: same as VisaJobs — board re-aggregates known boards; L5
  dedupHash on apply_url covers it; crawl after direct boards
