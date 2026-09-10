# Plan: VisaJobs (Affluent Indians Hub) Authenticated Source

## Overview

Ingest visa-sponsored jobs from the AIH "VisaJobs" app (`visajobs.affluentindianshub.com`). Discovery confirmed the jobs live in a Supabase Postgres `jobs` table (~36k rows, 5,642 visa-confirmed) behind Row-Level Security: a public anon key returns `[]`, so the OTP-derived user JWT is required. Supabase's long-lived refresh token means the login is genuinely 1-time — log in once (email+OTP), persist the `refresh_token`, and rotate the ~1h access token silently thereafter. The fetch is plain HTTP against `/rest/v1/jobs` using the existing `AiPageStrategy` `json_api` + `headers` path (the Personio precedent).

**Deduplication is a first-class requirement**: the jobs are re-aggregated from boards JobHunter already crawls directly (Arbeitnow, Jobgether, Workable, etc.), with each row's `url` pointing at the original board. The source must not surface duplicate jobs on the dashboard.

## Tech Stack

- Java 21, Spring Boot 3.3.5
- `WebClient` (existing) for the Supabase REST API; Jackson for JSON
- Supabase REST (PostgREST) `GET /rest/v1/jobs` with `apikey` + `Authorization: Bearer` headers
- Existing `AiPageStrategy` (CUSTOM `AtsType` + `ats_slug` JSON config: `json_api`, `headers`, `jobs_path`, `apply_base`)
- No new dependencies; a small credential-refresh helper if header-based static tokens prove too short-lived

## Testing Strategy

- Unit: `AiPageStrategyTest` extension asserting the `apikey` + `Authorization` headers are sent and `jobs_path` extraction + `apply_base` URL construction work against a WireMock-stubbed Supabase response.
- Credential rotation: unit test for the refresh helper (`POST /auth/v1/token?grant_type=refresh_token`) if implemented.
- Done when: manual crawl returns >0 visa jobs with non-null `applyUrl`/`externalId`; source `visa-exempt`; an expired access token is refreshed (or surfaces a clear re-login signal) rather than silently returning empty.

## Deduplication Strategy

The existing `AggregatorIngestionServiceImpl` already enforces cross-source dedup in three layers that apply directly here:

| Layer | Mechanism | Why it suppresses visajobs duplicates |
|---|---|---|
| L1 | `source + externalId` exact skip | Prevents re-ingesting the same visajobs row twice |
| L5 | `DedupHashUtil.compute(applyUrl)` — SHA-256 of normalized URL (case/query/trailing-slash-insensitive) | visajobs `url` points at the original board (arbeitnow.com, workable.com, …), so its hash collides with the `dedup_hash` of jobs already ingested from those boards |
| Fingerprint | `title + company + location` normalized | Matches existing ATS/aggregator jobs and **enriches** the existing row (adds `externalLinks`) instead of inserting a duplicate |

**Key decision (resolved in HLD)**: the visajobs source must route through the **aggregator ingestion path** (`AggregatorIngestionServiceImpl`), not the CUSTOM-endpoint `CrawlService` path — otherwise the cross-source `dedupHash`/fingerprint suppression is lost and duplicates will surface. This means the source is modeled as an **aggregator `JobSource`** (like WorkInFinland), not a CUSTOM `AtsType` endpoint, despite the fetch itself being a simple JSON API.

**Ordering risk**: dedup is directional — whichever source ingests first "wins." If visajobs is crawled before the direct board, the visajobs copy (thinner metadata) could supersede the richer direct-board entry. Mitigation: crawl visajobs *after* direct boards in the pipeline, and/or treat visajobs as a pure **enrichment signal** (`visa_confirmed`/`visa_type`) that annotates existing jobs rather than inserting new rows.

## Phases

### Phase 1: One-time login + credential capture (manual, already partly done)

- Step 1: Log into `learn.affluentindianshub.com` with email + OTP (email-delivered).
- Step 2: Capture the Supabase session (`localStorage` key `sb-svupfnfszawowpkqhwdn-auth-token`) — specifically `refresh_token`.
- Step 3: Persist `refresh_token` in config (env var / DB secret, not source control).

### Phase 2: Source modeling + dedup wiring (no code beyond config)

- Step 1: Model as an **aggregator source** (new `JobSource`/`DiscoverySource` enum values + YAML entry), so ingestion routes through `AggregatorIngestionServiceImpl` and inherits L1/L5/fingerprint dedup.
- Step 2: Configure the fetch (`json_api` + `headers` + `jobs_path` + `apply_base`) and `visa-exempt: true`.
- Step 3: Manual crawl + verify: (a) jobs persist with `externalId` from `job_id`; (b) a job already present from Arbeitnow/Workable is **not** duplicated (dedupHash/fingerprint suppression); (c) `visa_confirmed=Yes` maps to an appropriate visa signal.

### Phase 3: Token rotation (required — access token expires in ~1h)

- Step 1: Add a minimal credential-refresh step (config-driven `POST /auth/v1/token?grant_type=refresh_token`) that swaps the access token before/on 401.
- Step 2: Map 401-with-expired-refresh-token to a distinct `FetchResult` (re-login signal), never a silent empty.
- Step 3: WireMock unit tests for refresh + expiry paths.

## Risks/Edge cases

- **Duplicate jobs surfacing**: visajobs re-aggregates boards already crawled directly. Mitigation: route through aggregator ingestion (L1/L5/fingerprint dedup); crawl visajobs after direct boards; verify no dashboard duplicates in Phase 2.
- **Ordering/supersede**: visajobs copy may supersede a richer direct-board entry. Mitigation: treat visajobs as enrichment signal (annotate `visa_confirmed`) where the fingerprint already exists; schedule after direct boards.
- **Access token expiry (~1h) mid-crawl**: 401 on a page. Mitigation: Phase 3 refresh-before-fetch + on-401 retry once.
- **Refresh token revocation**: Supabase can revoke refresh tokens (password change, logout-all, admin). Mitigation: map to a clear re-login signal; manual re-capture is rare.
- **RLS policy change**: if the table becomes anon-readable or requires a paid tier, the auth model changes. Mitigation: monitor 200-vs-401 behavior; the anon-key-only test (`[]` vs rows) is a cheap health probe.
- **Secret exposure**: `refresh_token`/anon key in DB `ats_slug` is visible in backups. Mitigation: anon key is public (no concern); refresh token is the sensitive piece — prefer env-var indirection where the strategy supports it.
- **ToS/anti-bot**: scraping a members-only board. Mitigation: low frequency, bounded page size, polite delay; proceed only with user's explicit go-ahead.
- **`job_id` length**: synthetic keys like `arb_director-engineering-...-178878` can exceed 255 chars. Mitigation: reuse the existing SHA-256 `deriveExternalId` fallback.

## Decision Points

- **Aggregator source vs CUSTOM endpoint**: resolved — **aggregator source** (dedup correctness outweighs the simpler fetch path). The fetch still uses the `json_api`+`headers` mechanism internally.
- **Insert vs enrich**: whether visajobs inserts new rows or only annotates existing jobs — resolved in HLD based on how much net-new (non-duplicate) content exists after L5/fingerprint suppression.
- Credential storage (env var vs DB `ats_slug`) — env var preferred for the refresh token.
