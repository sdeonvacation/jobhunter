# Requirement: VisaJobs (Affluent Indians Hub) Authenticated Source

## Goal

Ingest visa-sponsored job postings from `https://learn.affluentindianshub.com/web/apps/visajobs` — the VisaJobs app embedded in the Affluent Indians Hub (AIH) learning platform — into the existing ingestion → filter → scoring pipeline.

## Background

AIH (`learn.affluentindianshub.com`) is a **TagMango white-label** "Creator's Platform" (Google Play app id `com.tagmango.affluentindianshub`, footer "Powered by TagMango"). `visajobs` is a custom application registered inside the TagMango dashboard, reachable at the `web/apps/visajobs` route.

The page is a **client-rendered SPA** ("You need to enable JavaScript to run this app"). Access requires a **1-time email + OTP login**. Unlike every existing JobHunter source, there is no public, unauthenticated JSON API.

The login/OTP flow and the underlying jobs data API have been **reverse-engineered and verified** (see "Verified API Contract" below). The jobs data lives in a Supabase Postgres table behind Row-Level Security; reading it requires the user's session JWT, obtained via a 1-time email+OTP login.

## Functional Requirements

| # | Requirement | Priority |
|---|---|---|
| FR1 | Discover the login flow (email → OTP → session token/cookie) and the jobs data API by actually logging in and capturing network traffic | Must |
| FR2 | Authenticate once (email + OTP) to obtain a reusable session credential | Must |
| FR3 | Persist the session credential so subsequent crawls do not re-trigger OTP | Must |
| FR4 | Fetch visa job postings through the authenticated API/HTML and map to `RawAggregatorJob` | Must |
| FR5 | Detect credential expiry and surface a clear re-login signal (do not silently fail) | Must |
| FR6 | Derive a stable `externalId` for dedup | Must |
| FR7 | Mark the source `visa-exempt` (visa-sponsored jobs are international by definition) | Must |
| FR8 | Register as a `JobSource` and `DiscoverySource` | Must |
| FR9 | Configurable via `application.yaml` / DB endpoint (no code change to toggle) | Must |

## Non-Functional Requirements

- No schema/migration changes unless a credential store is required (reuse existing config surface if possible).
- Prefer reusing `AiPageStrategy` (CUSTOM endpoint with `json_api` + `headers` + `post_body` + `jobs_path`) over a new strategy, per the Personio precedent.
- Respect the source: bounded page size, configurable delay, stop on max-results; no hammering.
- Graceful degradation: 401/403 (expired credential) and 429 (rate limit) map to distinct, observable `FetchResult` states.
- Secrets (session token/cookie) must not be committed to source control; read from env/config.

## Verified API Contract (discovered 2026-09-08)

- **App host**: `visajobs.affluentindianshub.com` (standalone React SPA, "AIH Curated Jobs"), gated by a TagMango login at `learn.affluentindianshub.com/web/login` (email → "Request OTP" → OTP → session).
- **Data backend**: Supabase. Project `https://svupfnfszawowpkqhwdn.supabase.co`, table `jobs` (~36,298 rows).
- **List endpoint**: `GET /rest/v1/jobs?select=*&hidden=eq.false&order=posted.desc.nullslast,job_id.desc&offset=0&limit=40`.
- **Auth headers**: `apikey: <anon-key>` (public, embedded in JS bundle) **and** `Authorization: Bearer <user-access-token>`.
- **RLS is enabled**: anon key alone returns `200 []`; the OTP-derived user JWT is required to see rows.
- **Row schema (21 columns)**: `job_id, title, company, location, remote, visa_confirmed, visa_type, seniority, experience, salary, employment, tech_stack, snippet, description_text, posted, source, ats, url, fetched_at, hidden, created_at`.
  - `job_id` is a stable synthetic key (e.g. `arb_...` for Arbeitnow-sourced, `work_...` for Workable-sourced) — usable as `externalId`.
  - `url` is the original source apply URL (e.g. arbeitnow.com, workable.com).
  - `description_text` carries the **full job description** — no separate enrichment fetch needed.
  - `visa_confirmed` ∈ {Yes, No}; `visa_type` (nullable); `remote` ∈ {Yes, No}; `tech_stack` comma-separated keywords; `seniority`, `experience`, `salary`, `employment`, `ats`, `source`.
- **Scrape verified**: 36,298 total rows; **5,642 with `visa_confirmed=Yes`**. Sources are boards JobHunter already crawls directly (Arbeitnow 694, Mindrift 140, Sperasoft 22, Speechify 11, CloudLinux 10, Seeq 11, Jobgether 2, …) — so this source is a curated "visa-confirmed" overlay, and cross-source dedup against existing sources is essential.
- **Session model** (Supabase auth-js, `localStorage` key `sb-svupfnfszawowpkqhwdn-auth-token`): `{ access_token, refresh_token, user, token_type, expires_in, expires_at }`.
  - Access token TTL ≈ **1 hour** (`expires_in: 3599`).
  - Refresh token is **opaque and long-lived** (Supabase refresh tokens do not expire by default).
  - Refresh flow: `POST /auth/v1/token?grant_type=refresh_token` with `{ refresh_token }` → new access token. **No OTP re-entry required.**

## Resolved Open Questions

| # | Question | Answer |
|---|---|---|
| Q1 | OTP delivery channel | Email (button "Request OTP"; JWT `amr: [otp]`) |
| Q2 | What login returns | Supabase `access_token` (1h) + long-lived `refresh_token` |
| Q3 | Where jobs live | Supabase REST API (`/rest/v1/jobs`), JSON — no HTML scraping needed |
| Q4 | Credential lifetime | Access token ~1h; refresh token effectively indefinite |
| Q5 | Entitlement gate | None beyond login — a free account can read all 36k jobs |

## Out of Scope

- Automating the **first** OTP retrieval (manual 1-time login is acceptable; the refresh token then removes any recurring OTP need).
- Scraping individual job detail pages (the list row already carries `url` + `description_text`; enrichment may still apply).
- Browser automation (Playwright/Selenium) — plain HTTP against the Supabase REST API suffices.
