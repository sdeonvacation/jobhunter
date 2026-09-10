# Requirement: GlobalMove (The Global Move) Authenticated Aggregator Source

## Background

`globalmove.relocate.me` ("The Global Move", tied to `relocateme.substack.com`) is a curated
relocation-focused job board (~1,850 jobs) gated behind a paid Substack subscription.
User is a subscriber (email `maurya.bitlegacy@gmail.com`) and wants the board ingested into
JobHunter as an aggregator source.

## Source facts (verified 2026-09-10 by live probing)

| Aspect | Finding |
|---|---|
| Stack | Laravel + Inertia (React), Cloudflare; robots.txt fully open |
| Auth | Passwordless magic link: `POST /magic-link {email}` → email → `GET /magic-link/authenticate?email&expires&signature` → session |
| Session | Cookies `the-global-move-session` (httponly) + `XSRF-TOKEN`; Max-Age 2h, **re-issued every response** (sliding) |
| Magic-link validity | Minutes (~10 min); expired links return "This magic link is invalid or has expired." |
| Data transport | Inertia page JSON embedded in `<script data-page="app" type="application/json">` |
| Routes | `/jobs` (paginated, 20/page), `/jobs/applied|saved|flagged`, `/jobs/counts`, `/jobs/{jobListing}/state` |
| Detail pages | **None** — no per-job description endpoint; list item is all available data |
| Job fields | `id, name, apply_url, categories[], posted_date, countries[], company, company_size, company_color, industry, linkedin_url, keywords[], work_mode, remote_types[], status` |
| posted_date | Relative string only: `"NEW"`, `"1d ago"` — no absolute timestamp |
| apply_url | Points at the ORIGINAL board (LinkedIn, Greenhouse, …) — excellent for cross-source dedup |

## Requirements

1. **One-time login**: session is captured once (user-assisted magic-link exchange) and stored
   outside source control (env vars). No re-login on subsequent runs.
2. **Session keepalive**: a lightweight scheduled ping (every ≤90 min) replays the stored
   cookie so the sliding 2h session never lapses.
3. **Aggregator ingestion**: model as `JobSource.GLOBAL_MOVE` aggregator source routed through
   `AggregatorIngestionServiceImpl` to inherit L1 (`source+externalId`), L5 (`apply_url`
   dedupHash) and fingerprint dedup — the board re-aggregates jobs JobHunter already crawls
   directly.
4. **Visa bypass**: the board is already visa-filtered → source is `visa-exempt: true`.
5. **Pagination**: follow `?page=N` up to `max-results` cap; stop when `next_page_url` is null.
6. **Re-login signal**: expired/invalid session maps to a distinct error state (never a silent
   empty result); re-capture is a rare manual step.
7. **No descriptions**: board offers none; existing aggregator enrichment may backfill later.
   Scoring runs on title/company/location metadata only.

## Non-goals

- Marking jobs saved/applied/flagged via `/jobs/{jobListing}/state`
- Scraping the public marketing site or Substack
- Automated email reading for magic links
