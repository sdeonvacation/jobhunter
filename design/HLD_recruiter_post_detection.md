# HLD: Recruiter Post Detection

## Overview

Given any job-opening URL (LinkedIn, Greenhouse, Lever, Ashby, Workday, SmartRecruiters, or an arbitrary ATS/aggregator link), determine whether a recruiter or hiring-team member posted about that specific opening on LinkedIn, with a confidence verdict. The check runs in **two modes sharing one pipeline**: **(1) automated** — after each 4-hourly pipeline tick's final scoring pass, an async batch checks the top N not-yet-checked filter-passing jobs using a slim call profile (~2 calls/job) within a daily budget; **(2) on-demand** — REST endpoint + MCP tool for arbitrary job URLs (~4-6 calls). Verified matches (HIGH/MEDIUM) are persisted as `OutreachContact` so the existing connect/message flow works immediately, and the digest dashboard surfaces the result asynchronously.

## Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Java 21 (Temurin) | Matches existing API; new service in `dev.jobhunter.linkedin` |
| Framework | Spring Boot 3.3.5 | Existing REST/DI patterns; `@ConditionalOnProperty` gating |
| LinkedIn access | Existing `HttpMcpClient` → linkedin-mcp sidecar | `search_posts`, `get_job_details`, `get_person_profile`, `search_people` |
| Rate limiting | Existing `LinkedInRateLimiter` (SEARCH/PROFILE buckets) | Prevent account restriction; budget enforcement |
| Job resolution | `JobPostingRepository.findByApplyUrl*`, `AtsDetector`, `StrategyRegistry` | Resolve arbitrary job URL → `JobContext` |
| AI tiebreaker | Existing `AiProvider` (Anthropic/OpenAI) | Borderline post classification (configurable, on by default) |
| Storage | PostgreSQL 16 + Liquibase | New `recruiter_post_check` cache table; reuse `outreach_contact` |
| MCP tool | TypeScript `@modelcontextprotocol/sdk` | `check_recruiter_post` tool → new REST endpoint |
| Testing | JUnit 5 + Mockito + WireMock (API), Vitest (MCP) | Unit, integration, manual E2E |

## Components

| Component | Responsibility | Dependencies |
|-----------|---------------|-------------|
| `RecruiterPostDetectionService` | Orchestrates the full check: cache lookup → job resolution → post search → scoring → AI tiebreaker → persistence | `HttpMcpClient`, `LinkedInRateLimiter`, `JobContextResolver`, `PostSearchService`, `SignalScorer`, `AiProvider`, `RecruiterPostCheckRepository`, `OutreachContactRepository` |
| `JobContextResolver` | Resolves any job URL → `JobContext` (title, company, location, postedDate, applyUrl, atsType, linkedinJobId) via DB / `get_job_details` / `FetchStrategy` | `JobPostingRepository`, `HttpMcpClient`, `AtsDetector`, `StrategyRegistry` |
| `PostSearchService` | Builds 2-3 `search_posts` query variants with recency filters; collects + dedups candidates by post URL | `HttpMcpClient`, `LinkedInRateLimiter` |
| `SignalScorer` | Scores each candidate post on 5 signals → confidence verdict (HIGH/MEDIUM/UNCERTAIN/NOT_FOUND) | — (pure logic) |
| `AuthorRoleClassifier` | Classifies post author as recruiter/hiring/founder/eng-lead from name + title keywords | — (pure logic) |
| `RecruiterPostAiTiebreaker` | LLM classification for MEDIUM/UNCERTAIN candidates ("does this post refer to this specific opening?") | `AiProvider` |
| `RecruiterPostCheck` (entity) | Cache row: verdict + evidence JSONB + TTL | — |
| `RecruiterPostCheckRepository` | Cache read/write, TTL expiry | Spring Data JPA |
| `LinkedInController` (extended) | New `POST /api/linkedin/recruiter-post-check` endpoint (on-demand) + cache-only batch read endpoint for digest cards | `RecruiterPostDetectionService`, `RecruiterPostCheckRepository` |
| `check_recruiter_post` (MCP tool) | TypeScript tool → REST endpoint | `JobHunterClient` |
| `RecruiterPostDetectionScheduler` | Post-pipeline-tick trigger (async, not Quartz): selects top-N not-yet-checked jobs from today's KEEP set by score, runs slim batch within daily budget, skips fresh-cached/session-invalid, resumes next tick | `PipelineScheduler`, `JobPostingRepository`, `OpportunityScoreRepository`, `RecruiterPostDetectionService`, `LinkedInRateLimiter`, config (`linkedin-mcp.recruiter-post-check.automated.*`) |
| `DigestRecruiterBlock` (dashboard) | React component on `DailyDigest` job cards; fetches cached recruiter-post results (cache-only) and renders recruiter block for HIGH/MEDIUM; silent otherwise | `api.linkedin.getRecruiterPostChecks`, `JobCard` |

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          JobHunter Platform                                   │
│                                                                                │
│  AUTOMATED (post-pipeline-tick)               ON-DEMAND                         │
│  ┌───────────────┐   ┌───────────────────┐   ┌──────────────┐                  │
│  │ Pipeline       │──▶│ RecruiterPost      │   │  MCP tool     │                │
│  │ Scheduler      │   │ DetectionScheduler │   │ check_        │                │
│  │ (4h tick,      │   │ (async, top-N +    │   │ recruiter_post│                │
│  │  async dispatch│   │  budget)           │   │               │                │
│  └───────────────┘   └─────────┬─────────┘   └──────┬───────┘                │
│                                │ (batch, slim)       │ (single URL, 4-6 calls)│
│                      ┌─────────▼─────────┐   ┌───────▼────────────────────┐   │
│                      │ LinkedInController │   │ LinkedInController          │   │
│                      │ (batch run)        │   │ POST /recruiter-post-check  │   │
│                      └─────────┬─────────┘   └───────┬────────────────────┘   │
│                                └──────────┬──────────┘                         │
│                                           ▼                                     │
│                        ┌─────────────────────────────────────┐                  │
│                        │  RecruiterPostDetectionService       │                  │
│                        │  (orchestrator + budget guard)       │                  │
│                        └───────┬───────────────┬─────────────┘                  │
│                                │               │                                │
│              ┌─────────────────▼───┐   ┌───────▼────────────────┐               │
│              │  JobContextResolver  │   │  PostSearchService      │               │
│              │  (DB / get_job_      │   │  (search_posts variants;│               │
│              │   details / Fetch)   │   │   slim = single variant)│               │
│              └─────────────────┬───┘   └───────┬────────────────┘               │
│                                │               │                                │
│                        ┌───────▼───────────────▼───────┐                         │
│                        │  SignalScorer + AuthorRole    │                         │
│                        │  Classifier                  │                         │
│                        └───────┬───────────────────────┘                         │
│                                │ (MEDIUM/UNCERTAIN)                             │
│                        ┌───────▼───────────────┐  AiProvider                     │
│                        │  RecruiterPostAiTiebreaker │                          │
│                        └───────┬───────────────┘                                 │
│                                │                                                 │
│                        ┌───────▼──────────────────────────┐                      │
│                        │  Persistence                      │◀──────────────────┐  │
│                        │  (RecruiterPostCheck cache +      │                   │  │
│                        │   OutreachContact)                │                   │  │
│                        └──────────────────────────────────┘                   │  │
│                                                                              │  │
│  DASHBOARD READ (async, cache-only)                                           │  │
│  ┌───────────────────────────┐   ┌───────────────────────────┐               │  │
│  │ DailyDigest page          │──▶│ DigestRecruiterBlock       │───────────────┘  │
│  │ (renders immediately)     │   │ (recruiter block on card)  │ POST batch-read  │
│  └───────────────────────────┘   └───────────────────────────┘  (zero LinkedIn) │
│                                                                                │
│  ┌───────────────────────────────────────────────────────────────────────┐     │
│  │  LinkedInRateLimiter (SEARCH/PROFILE/ACTION buckets)                  │     │
│  └──────────────────────────────────┬────────────────────────────────────┘     │
│                          ┌──────────▼──────────┐                               │
│                          │  HttpMcpClient       │                               │
│                          └──────────┬──────────┘                               │
└─────────────────────────────────────┼──────────────────────────────────────────┘
                                      │ HTTP POST :8000/mcp
┌─────────────────────────────────────▼──────────────────────────────────────────┐
│                    linkedin-mcp (Docker Sidecar)                                │
│   search_posts · get_job_details · get_person_profile · search_people           │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Description**: The orchestrator (`RecruiterPostDetectionService`) is the single entry point for both modes. On-demand mode (MCP tool / REST endpoint) issues ~4-6 calls per arbitrary URL; automated mode is driven by `RecruiterPostDetectionScheduler`, which `PipelineScheduler` dispatches asynchronously after each 4-hourly tick's final scoring pass — it selects the top N not-yet-checked jobs from today's KEEP set by OpportunityScore and drives slim (~2 calls/job) batch checks within a daily budget. Async dispatch keeps pipeline duration unaffected; a dedicated concurrency guard prevents overlapping batches. The orchestrator enforces a per-check call budget and delegates to three focused collaborators: `JobContextResolver` (URL → structured job context), `PostSearchService` (query variants → candidate posts; single variant in slim mode), and `SignalScorer` (signals → verdict). The `AiProvider` is invoked only for borderline (MEDIUM/UNCERTAIN) candidates. Persistence is split: `RecruiterPostCheck` caches the verdict keyed by job URL (TTL 7 days), and `OutreachContact` is created/linked for HIGH/MEDIUM matches. The digest dashboard (`DailyDigest` → `DigestRecruiterBlock`) reads only cached results through a cache-only batch endpoint — it never triggers LinkedIn calls, so cards render immediately and recruiter blocks appear on the next load/refresh after the background run. All LinkedIn access flows through the existing `HttpMcpClient` + `LinkedInRateLimiter` unchanged.

## Interfaces

### RecruiterPostDetectionService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| checkRecruiterPost | String jobUrl, boolean force | `RecruiterPostCheckResult` | Cache lookup (skip if fresh + !force) → resolve context → search → score → AI tiebreaker → persist → result | Returns result with `UNRESOLVED` status on unresolvable URL; `NOT_FOUND` on no posts; `UNCERTAIN` on budget exhaustion |
| getCallsUsed | — | int | Returns LinkedIn MCP calls consumed this check | — |

### JobContextResolver

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| resolve | String jobUrl | `Optional<JobContext>` | 1) DB `findFirstByApplyUrl` / `findFirstByApplyUrlStartingWith`; 2) LinkedIn URL → `get_job_details`; 3) ATS URL → `AtsDetector` + `StrategyRegistry.getStrategy(type).fetch()` | `Optional.empty()` on unresolvable/dead/auth-walled URL (no LinkedIn calls wasted) |

### PostSearchService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| searchCandidates | JobContext ctx, int maxCalls | List\<CandidatePost\> | Builds 2-3 `search_posts` variants (company+title, company+hiring+role, title-only fallback) with recency filter (past-week/past-month); dedups by post URL; stops when budget exhausted | Returns partial list on rate-limit/budget exhaustion; empty list on MCP failure |

### SignalScorer

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| score | CandidatePost post, JobContext ctx | `SignalScores` | Computes 5 signals: link match (strongest), company-name match, fuzzy title match, author-role classification, recency window | — |
| verdict | SignalScores scores | `ConfidenceVerdict` | Maps scores → HIGH / MEDIUM / UNCERTAIN / NOT_FOUND | — |

### AuthorRoleClassifier

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| classify | String authorName, String authorTitle | `AuthorRole` | Keyword match against recruiter/talent/hiring/founder/eng-lead vocab → role enum | UNKNOWN on no match |

### RecruiterPostAiTiebreaker

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| classify | CandidatePost post, JobContext ctx | `AiVerdict` (YES/NO/UNKNOWN) | LLM prompt: "does this post refer to this specific opening?" | Returns UNKNOWN on `AiProvider` unavailable/error (falls back to signal verdict) |

### RecruiterPostCheckRepository

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| findByJobUrlAndExpiresAtAfter | String jobUrl, LocalDateTime now | Optional\<RecruiterPostCheck\> | Fresh cache lookup | — |
| deleteByExpiresAtBefore | LocalDateTime now | void | TTL cleanup | — |
| findByJobUrlInAndExpiresAtAfter | List\<String\> jobUrls, LocalDateTime now | List\<RecruiterPostCheck\> | Fresh cache lookup for a batch of URLs (digest read path) | — |

### RecruiterPostDetectionScheduler

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| execute | — (invoked by PipelineScheduler, not Quartz) | void | Async dispatch target after pipeline's final scoring pass; no-op if `automated.enabled=false`; skips run if `HttpMcpClient.isSessionValid()` is false; own concurrency guard (never two batches at once); otherwise selectTopNJobs → runBatch; logs summary | Never throws — logs + skips (retries next tick) |
| selectTopNJobs | int limit | List\<JobPosting\> | Queries digest-set jobs (filter-passing `KEEP`, discovered today, active, not applied, not hidden) ordered by `OpportunityScore` desc, takes top `limit` | Empty list when no eligible jobs |
| runBatch | List\<JobPosting\> jobs | BatchRunSummary (checked, skippedCached, skippedBudget, callsUsed) | For each job: skip if fresh cache hit; enforce slim mode (single best query variant); decrement shared daily budget; persist via `RecruiterPostDetectionService` | Stops early on budget exhaustion; skips job on session-invalid/rate-limit block |

### LinkedInController (extended)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| POST /api/linkedin/recruiter-post-check | `RecruiterPostCheckRequest(url, force?)` | `RecruiterPostCheckResult` | On-demand check; delegates to service; 200 on verdict, 400 on blank URL, 429 on session invalid | `ResponseEntity` mapping per status |
| POST /api/linkedin/recruiter-post-check/batch-read | `BatchReadRequest(List<String> jobUrls)` | `List<RecruiterPostCheckResult>` | **Cache-only read** for digest cards: returns fresh cached verdicts for the given URLs; absent/expired URLs omitted; **never issues LinkedIn calls** (0 calls) | 400 on empty list; returns empty list when nothing cached |

## Data Flow

### Happy Path (HIGH confidence)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | MCP tool / Controller | Receive job URL, call service | RecruiterPostDetectionService |
| 2 | RecruiterPostDetectionService | Check `recruiter_post_check` cache (fresh + !force) | JobContextResolver (on miss) |
| 3 | JobContextResolver | Resolve URL → JobContext (DB → get_job_details → FetchStrategy) | PostSearchService |
| 4 | PostSearchService | Issue 2-3 `search_posts` calls with recency filter, dedup | SignalScorer |
| 5 | SignalScorer | Score each candidate on 5 signals | verdict mapping |
| 6 | RecruiterPostDetectionService | HIGH verdict (link match OR company+title+recruiter-author) | Persistence |
| 7 | Persistence | Save `RecruiterPostCheck` (TTL 7d); create/link `OutreachContact` | Response |
| 8 | Controller | Return verdict + matched posts + contactId + callsUsed | MCP tool / client |

### Borderline Path (MEDIUM/UNCERTAIN → AI tiebreaker)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 5a | SignalScorer | Verdict MEDIUM/UNCERTAIN | RecruiterPostAiTiebreaker |
| 5b | RecruiterPostAiTiebreaker | LLM classifies post vs opening | verdict refinement |
| 6 | RecruiterPostDetectionService | Apply refined verdict; HIGH/MEDIUM → persist contact | Persistence |

### Automated Post-Pipeline-Tick Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineScheduler | Complete crawl + sources + final scoring pass (existing 4h tick) | RecruiterPostDetectionScheduler (async dispatch) |
| 2 | RecruiterPostDetectionScheduler | Async run starts; no-op if `automated.enabled=false` or session invalid; else continue | selectTopNJobs |
| 3 | RecruiterPostDetectionScheduler | Select top N not-yet-checked jobs from today's KEEP set by OpportunityScore | runBatch |
| 4 | runBatch | Per job: skip fresh cache; enforce slim mode; decrement daily budget | PostSearchService (slim) |
| 5 | PostSearchService (slim) | Single best query variant (`"{company}" "{title}"`) + recency filter | SignalScorer |
| 6 | SignalScorer | Score candidate posts → verdict | Persistence |
| 7 | Persistence | Save `RecruiterPostCheck` (TTL 7d); create/link `OutreachContact` for HIGH/MEDIUM | batch summary |
| 8 | RecruiterPostDetectionScheduler | Stop at daily budget cap; log `BatchRunSummary`; unused budget carries to next tick | — |

### Digest Card Read Flow (cache-only)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | DailyDigest page | Render today's job cards immediately (no recruiter data) | — |
| 2 | DigestRecruiterBlock | On mount, call batch-read with visible job URLs | LinkedInController |
| 3 | LinkedInController | Read fresh `RecruiterPostCheck` rows only (**0 LinkedIn calls**) | return cached results |
| 4 | DigestRecruiterBlock | Render recruiter block for HIGH/MEDIUM (name, title, snippet, "posted Xd ago", post link) + Connect/Message via contactId; **silent** for no-match / not-yet-checked / UNCERTAIN | — |

**Error Flows**:
- **Unresolvable URL** (dead link, auth-walled ATS): `JobContextResolver` returns empty → result status `UNRESOLVED`, **zero** LinkedIn calls.
- **Session invalid**: `HttpMcpClient.isSessionValid()` false → fail fast with clear error (HTTP 429) before any `search_posts` call.
- **Rate-limit/budget exhaustion mid-check**: budget counter aborts, returns partial results with verdict `UNCERTAIN` + `callsUsed`.
- **search_posts noise** (aggregators, unrelated roles): keyword-only matches never exceed `UNCERTAIN` (link/company+title required for higher).
- **AiProvider unavailable**: tiebreaker returns UNKNOWN, falls back to signal verdict.
- **Post deleted between check and outreach**: post URL + snippet stored at check time; stale cache handled conservatively (force flag re-checks).
- **Automated run disabled or session invalid**: scheduler no-ops or skips, logs, retries next cycle — no LinkedIn calls.
- **Daily budget exhausted mid-batch**: `runBatch` stops early, already-checked jobs persist; remaining jobs resume next cycle (partial batch).
- **Rate-limit block mid-batch**: `acquireOrWait` times out → skip job, defer to next cycle; does not fail the whole run.
- **Batch read with nothing cached / expired rows**: returns empty list → dashboard renders silently (no visual noise).

## Data Model

### RecruiterPostCheck (new entity → table `recruiter_post_check`)

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, auto-generated |
| job_url | VARCHAR(2048) | UNIQUE, NOT NULL |
| verdict | ENUM (HIGH, MEDIUM, UNCERTAIN, NOT_FOUND, UNRESOLVED) | NOT NULL |
| confidence | DOUBLE | NOT NULL |
| result_data | JSONB | NOT NULL (matched posts, evidence, contactId, callsUsed) |
| checked_at | TIMESTAMP | NOT NULL |
| expires_at | TIMESTAMP | NOT NULL (checked_at + 7 days) |
| created_at | TIMESTAMP | NOT NULL, auto |

### OutreachContact (reused, no schema change)

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK |
| company_id | UUID | FK → company.id, NULLABLE (nullable since changeset 017) |
| linkedin_url | VARCHAR(500) | UNIQUE, NOT NULL (dedup key) |
| person_name | VARCHAR(255) | NOT NULL |
| title | VARCHAR(500) | |
| connection_status | ENUM (NONE, PENDING, CONNECTED, DECLINED) | NOT NULL, default NONE |
| notes | TEXT | Post URL + snippet + matched job URL |
| discovered_via | ENUM | RECRUITER_POST (**new enum value** — extend `ContactDiscoverySource` with `RECRUITER_POST`; string-valued column, no DDL change) |

### JobPosting (existing poster columns, reused for linkage)

| Field | Type | Constraints |
|-------|------|-------------|
| poster_contact_id | UUID | FK → outreach_contact.id, NULLABLE |

**Linkage decision**: Reuse `OutreachContact` as-is. No new FK on `recruiter_post_check` is required — the contact id is stored inside `result_data` JSONB, and the `job_contact` join table (existing `linkContactToJob`) links `JobPosting` ↔ `OutreachContact` when the job URL resolves to a known `JobPosting`. `JobPosting.posterContactId` is set when applicable.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Search query variants | 2-3 `search_posts`: `"{company}" "{title}"`, `"{company}" hiring "{role}"`, title-only fallback | Maximizes recall while bounding calls; company+title is the highest-precision query | Single broad query; 4+ variants | More variants = more calls; 3 keeps within 4-6 call budget |
| Confidence thresholds | HIGH = link match OR (company+title+recruiter-author); MEDIUM = company+title; UNCERTAIN = keyword-only; NOT_FOUND = no candidates | Link match is unambiguous; company+title+role is strong evidence; keyword-only is noise | Weighted numeric score only | Explicit tiers are auditable and map cleanly to outreach actions |
| AI tiebreaker placement | Only for MEDIUM/UNCERTAIN candidates, after signal scoring | AI is expensive/slow; only borderline cases need disambiguation | AI-first classification of all posts | Saves tokens/latency; signal scorer is deterministic baseline |
| Cache TTL | 7 days, keyed by job URL | Posting landscape changes slowly; mirrors `ProfileCache` 7-day pattern | Shorter (1d) or longer (30d) | 7d balances freshness vs call savings; `force` flag bypasses |
| Budget enforcement point | Orchestrator-level counter decremented on every `HttpMcpClient.callTool` | Single choke point guarantees 4-6 call cap regardless of path | Per-service counters | Central counter is simpler to reason about and test |
| Reuse OutreachContact | Reuse as-is; add `RECRUITER_POST` to `ContactDiscoverySource` enum (no DDL); dedup by `linkedin_url` | Existing connect/message flow works immediately; avoids redundant entity | New `RecruiterPost` entity | Reuse is zero-migration and matches existing `parseContactResults` pattern |
| Job resolution order | DB → `get_job_details` → `FetchStrategy` | DB is cheapest and most accurate; LinkedIn path covers LinkedIn URLs; ATS path covers external ATS | Uniform `FetchStrategy` for all | DB-first avoids unnecessary scraping for already-tracked jobs |
| Scheduler trigger point | Async dispatch from `PipelineScheduler.runPipelineInternal()` after the final scoring pass (not a separate Quartz cron) | The daily digest page is a live query over today's KEEP jobs — there is no digest artifact to chain from; the pipeline tick is the actual "end of pipeline". Async dispatch keeps pipeline duration unaffected; no clock-race risk | Separate Quartz cron offset (e.g. `0 30 */4 * * ?`); synchronous call at end of pipeline | Chosen async dispatch: robust to pipeline overrun, zero blast radius on pipeline path, detection slow-downs never block crawl/scoring. Sync call rejected (would extend pipeline by minutes); separate cron rejected (clock-race with pipeline tick) |
| Top-N selection criterion | Order today's not-yet-checked KEEP jobs by `OpportunityScore` desc, take top `top-n` | Matches the digest page's live-query semantics (KEEP, discovered today, active, not applied, not hidden); re-queried at run time, not snapshotted | `MatchScore` ordering (what the digest page sorts by today) | OpportunityScore is the composite fit metric; matchScore is skill-only. Chose OpportunityScore for higher-value recruiter targets |
| Slim mode | Automated checks use a single best query variant (`"{company}" "{title}"`) → ~2 calls/job (1 search + ≤1 profile lookup), vs on-demand 2-3 variants | Digest-set jobs already resolve from DB (0 resolution calls); company+title is the highest-precision variant | Full variant set for automated mode | Slim keeps the batch within budget; slightly lower recall is acceptable since automated mode targets already high-scored jobs |
| Daily budget cap | `automated.daily-call-budget: 40`, **shared** with on-demand checks (single `LinkedInRateLimiter` + `total-per-hour: 50` already gate both) | Single global budget prevents automated batch from starving on-demand; 40 = 2× the ~20 calls needed for top-10 × ~2 | Separate automated budget; higher cap | Shared cap is simpler (one rate limiter); reserving a slice for on-demand is handled by the existing `total-per-hour` bucket |
| Digest read endpoint shape | `POST /api/linkedin/recruiter-post-check/batch-read` (cache-only, returns `List<RecruiterPostCheckResult>`), not per-job GET | Batch = 1 request for ~50 visible cards; POST-with-body avoids URL-length limits (apply URLs up to 2048 chars each); cache-only guarantees 0 LinkedIn calls | Per-job `GET /cached?url=`; GET with repeated `?url=` params | Batch POST is pragmatic (long URLs); slight semantic mismatch (read via POST) accepted for practical limits |
| Async digest UX | Cards render immediately; `DigestRecruiterBlock` fetches cache-only on mount and renders **only** for HIGH/MEDIUM; silent for no-match/not-yet-checked/UNCERTAIN | No flicker (silent = nothing rendered, no spinner); results arrive on next refresh/poll after background run | Blocking load (spinner per card); eager fetch of all checks | Silent-async avoids visual noise and layout shift; tradeoff is recruiter block may lag page render until background run completes |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| search_posts keyword noise (aggregators, unrelated roles) | False positives inflate confidence | High | Multi-signal scoring; keyword-only matches capped at UNCERTAIN |
| Company-name ambiguity ("Nova" matches many companies) | Wrong company matched | Medium | Require exact normalized company token + author-company cross-check |
| Job title variants ("Senior Java Engineer" vs "Senior Java Backend Developer") | Missed true matches | Medium | Fuzzy title matching + AI tiebreaker |
| Rate-limit exhaustion mid-check | Partial results, no verdict | Medium | Budget counter aborts gracefully with UNCERTAIN + partial evidence |
| Session invalid | All calls fail | Medium | `isSessionValid()` fail-fast before any search |
| Unresolvable URL (dead link, auth-walled ATS) | Wasted calls | Medium | `JobContextResolver` returns UNRESOLVED with zero LinkedIn calls |
| Stale cache (post deleted after check) | Outreach to dead post | Low | Store post URL + snippet at check time; `force` flag re-checks |
| LinkedIn ToS | Account restriction | Low | On-demand, user-initiated, read-only, within existing rate-limit buckets |
| Automated batch exhausting rate budget | Batch consumes the daily/hourly budget, starving on-demand checks | Medium | `automated.daily-call-budget: 40` cap + `total-per-hour: 50` bucket; run stops at cap and resumes next cycle |
| Contention between automated run and on-demand checks | Concurrent calls exceed rate-limit buckets → account throttle | Medium | Shared `LinkedInRateLimiter` buckets (`acquire`/`acquireOrWait`) serialize both modes; daily budget reserves headroom; automated batch runs async after pipeline tick, on-demand is user-initiated and rarely concurrent |
| Partial digest UX (results arrive after page load) | Recruiter block missing on first paint, appears only after background run + refresh | Medium | Silent no-match (no spinner/flicker); cache-only batch read is cheap so cards can re-poll on next navigation/refresh |
| Automated run processes stale job set (job already applied/hidden by run time) | Wasted calls on jobs no longer actionable | Low | `selectTopNJobs` re-queries filter-passing/not-applied/not-hidden at run time (live query, no snapshot) |
| Detection batch slows pipeline | Pipeline tick duration inflated by slow browser-automation calls | Low | Async dispatch after final scoring pass; pipeline completes before batch starts; own concurrency guard prevents overlap |

## Test Plan

### Unit Tests

**JobContextResolver** (mock `HttpMcpClient`, `JobPostingRepository`, `AtsDetector`, `StrategyRegistry`):
- DB hit: `findFirstByApplyUrl` returns JobPosting → JobContext mapped correctly
- DB prefix hit: `findFirstByApplyUrlStartingWith` matches job with query-string URL
- LinkedIn URL: `get_job_details` response parsed → JobContext (title, company, linkedinJobId)
- ATS URL: `AtsDetector` detects GREENHOUSE → `GreenhouseStrategy.fetch` → JobContext
- Unresolvable: dead link → `Optional.empty()`; verify **no** `callTool` invoked

**PostSearchService** (mock `HttpMcpClient`):
- Query variants built correctly (company+title, company+hiring+role, title-only)
- Recency filter applied (past-week vs past-month selection)
- Dedup by post URL across variants
- Budget exhaustion: stops after N calls, returns partial list

**SignalScorer** (pure unit):
- Link match → HIGH regardless of other signals
- Company+title+recruiter-author → HIGH
- Company+title, author unclear → MEDIUM
- Keyword-only → UNCERTAIN
- No candidates → NOT_FOUND
- Each signal scored independently (5 separate cases)

**AuthorRoleClassifier** (pure unit):
- recruiter/talent/hiring keywords → RECRUITER
- founder/eng-lead keywords → respective roles
- No match → UNKNOWN

**RecruiterPostAiTiebreaker** (mock `AiProvider`):
- Provider returns YES → verdict refined upward
- Provider returns NO → verdict refined downward
- Provider unavailable → UNKNOWN, falls back to signal verdict

**RecruiterPostDetectionService** (mock all collaborators):
- Cache hit (fresh) → 0 LinkedIn calls, returns cached verdict
- `force=true` → bypasses cache
- HIGH/MEDIUM → OutreachContact created, deduped by linkedin_url
- Budget counter decrements correctly

**RecruiterPostDetectionScheduler** (mock `JobPostingRepository`, `OpportunityScoreRepository`, `RecruiterPostDetectionService`, `LinkedInRateLimiter`):
- `automated.enabled=false` → async dispatch no-ops (no repository/service calls)
- Session invalid (`isSessionValid() == false`) → run skips, logs, no LinkedIn calls
- Concurrency guard: second dispatch while batch running → no-op (no double batch)
- Top-N ordering: `selectTopNJobs` returns jobs ordered by OpportunityScore desc, truncated to `top-n`
- Filter-passing only: applied/hidden/non-KEEP/non-today jobs excluded from selection; re-queried live at run time
- Budget cap: `runBatch` stops when daily budget exhausted, returns summary with remaining unprocessed
- Skip-cached: jobs with fresh `RecruiterPostCheck` skipped (0 calls), counted in `skippedCached`
- Slim mode: exactly one `search_posts` variant issued per job (not 2-3)
- Partial batch: rate-limit block on one job skips it, continues with next (no full-run failure)
- Pipeline non-interference: dispatch happens after final scoring pass; pipeline duration unaffected (async)

### Dashboard Component Tests (Vitest)

**DigestRecruiterBlock** (mock `api.linkedin.getRecruiterPostChecks`):
- Found: verdict HIGH/MEDIUM → renders recruiter block (name, title, snippet, "posted Xd ago", post link) + Connect/Message actions bound to `contactId`
- Silent no-match: verdict NOT_FOUND/UNCERTAIN or absent from batch response → renders nothing
- Silent not-yet-checked: URL absent from batch response (not yet run) → renders nothing
- Loading: no spinner/flicker while the cache-only fetch is in flight (component returns `null` until data arrives)

### Integration Tests

- **REST endpoint + WireMock**: simulate `search_posts` + `get_job_details` JSON-RPC responses through `POST /api/linkedin/recruiter-post-check`; verify full lifecycle (resolve → search → score → persist)
- **Cache TTL**: expired `recruiter_post_check` row triggers re-fetch; fresh row returns cached verdict
- **Contact persistence**: HIGH/MEDIUM match creates `OutreachContact` row (Testcontainers PostgreSQL); duplicate URL reuses existing contact
- **Rate limiter interaction**: SEARCH bucket exhaustion mid-check → partial results + UNCERTAIN
- **Batch read endpoint (cache-only)**: seed `recruiter_post_check` rows via Testcontainers; `POST /api/linkedin/recruiter-post-check/batch-read` returns only fresh cached verdicts and **issues zero sidecar calls** (assert `HttpMcpClient.callTool` never invoked); expired/absent URLs omitted

### End-to-End Tests

- **Critical journeys**: (1) Greenhouse URL → recruiter post found with HIGH confidence; (2) LinkedIn URL → recruiter post found; (3) arbitrary URL → NOT_FOUND; (4) repeat check within TTL → 0 LinkedIn calls (manual, live sidecar)
- **Success criteria**: `check_recruiter_post` returns verdict + evidence for arbitrary job URLs; repeat checks hit cache; HIGH/MEDIUM matches create `OutreachContact` rows; all unit/integration tests pass

### Non-Functional Tests

- **Performance**: single check ≤ ~6 LinkedIn calls; cache hit path returns in <50ms (no sidecar round-trip)
- **Security**: input URL validation (reject blank/malformed); AI tiebreaker prompt injection guarded by fixed system prompt
- **Scalability**: cache table indexed on `job_url` (unique) + `expires_at` (cleanup); no shared mutable state in scorers (thread-safe)

## Reference Interfaces

### Java — RecruiterPostDetectionService + DTOs

```java
package dev.jobhunter.linkedin;

public interface RecruiterPostDetectionService {

    RecruiterPostCheckResult checkRecruiterPost(String jobUrl, boolean force);

    record RecruiterPostCheckResult(
        String jobUrl,
        Verdict verdict,                 // HIGH, MEDIUM, UNCERTAIN, NOT_FOUND, UNRESOLVED
        double confidence,
        List<MatchedPost> matchedPosts,
        UUID contactId,                  // nullable — set when HIGH/MEDIUM persisted
        int callsUsed
    ) {}

    record MatchedPost(
        String postUrl,
        String authorName,
        String authorTitle,
        String authorLinkedinUrl,
        String snippet,
        String postedAt
    ) {}

    enum Verdict { HIGH, MEDIUM, UNCERTAIN, NOT_FOUND, UNRESOLVED }
}
```

### Java — JobContextResolver

```java
package dev.jobhunter.linkedin;

import dev.jobhunter.model.enums.AtsType;
import java.time.LocalDate;
import java.util.Optional;

public interface JobContextResolver {

    Optional<JobContext> resolve(String jobUrl);

    record JobContext(
        String title,
        String company,
        String location,
        LocalDate postedDate,
        String applyUrl,
        AtsType atsType,
        String linkedinJobId        // nullable — set for LinkedIn URLs
    ) {}
}
```

### Java — SignalScorer + AuthorRoleClassifier

```java
package dev.jobhunter.linkedin;

public interface SignalScorer {

    SignalScores score(CandidatePost post, JobContextResolver.JobContext ctx);

    Verdict verdict(SignalScores scores);

    record SignalScores(
        boolean linkMatch,          // strongest
        boolean companyNameMatch,
        boolean fuzzyTitleMatch,
        AuthorRole authorRole,
        boolean recencyMatch
    ) {}

    record CandidatePost(
        String postUrl, String authorName, String authorTitle,
        String authorLinkedinUrl, String snippet, String postedAt
    ) {}
}

enum AuthorRole { RECRUITER, HIRING_MANAGER, FOUNDER, ENG_LEAD, UNKNOWN }
```

### Java — REST request record (in LinkedInController)

```java
public record RecruiterPostCheckRequest(String url, Boolean force) {}
// POST /api/linkedin/recruiter-post-check
```

### TypeScript — MCP tool input schema

```typescript
// mcp-server/src/tools/checkRecruiterPost.ts
import { z } from 'zod';

const inputSchema = z.object({
  url: z.string().describe('Job posting URL (LinkedIn, Greenhouse, Lever, Ashby, etc.)'),
  force: z.boolean().optional().default(false)
    .describe('Bypass the 7-day cache and re-check'),
});

export const checkRecruiterPostTool = {
  name: 'check_recruiter_post',
  description: 'Check whether a recruiter or hiring-team member posted about a specific job opening on LinkedIn. Returns verdict (HIGH/MEDIUM/UNCERTAIN/NOT_FOUND), matched posts with author/post URL/snippets, contactId if saved, and callsUsed.',
  inputSchema,
  handler: async (params, client) => {
    const result = await client.checkRecruiterPost(params.url, params.force);
    // format verdict + matched posts + contactId + callsUsed
    return { content: [{ type: 'text', text: formatted }] };
  },
};
```

### TypeScript — client method

```typescript
async checkRecruiterPost(url: string, force?: boolean): Promise<any> {
  return this.request('/api/linkedin/recruiter-post-check', {
    method: 'POST',
    body: JSON.stringify({ url, force }),
  });
}
```

## Phase-to-Architecture Mapping

| Phase | Components Created | Config Changes | DB Migrations |
|-------|-------------------|----------------|---------------|
| 1: Job Context Resolution | `JobContextResolver`, `JobContext` record | — | None |
| 2: Post Search + Verification | `PostSearchService`, `SignalScorer`, `AuthorRoleClassifier`, `RecruiterPostAiTiebreaker` | `linkedin-mcp.recruiter-post-check.*` (max-calls, ttl-days, ai-verification-enabled, recency-window) | None |
| 3: Persistence | `RecruiterPostCheck` entity, `RecruiterPostCheckRepository` | — | Changeset 019 (`recruiter_post_check` table) |
| 4: Exposure | `RecruiterPostDetectionService`, controller endpoint, `check_recruiter_post` MCP tool, client method | — | None |
| 5: Automated Pipeline | `RecruiterPostDetectionScheduler` (async dispatch from `PipelineScheduler`), concurrency guard | `recruiter-post-check.automated.{enabled, top-n, daily-call-budget, slim-mode}` | None |
| 6: Digest UX | `DigestRecruiterBlock`, `api.linkedin.getRecruiterPostChecks` client method, `batch-read` controller endpoint | — | None |

**Config additions** (`application.yaml`, under `linkedin-mcp`):

```yaml
linkedin-mcp:
  recruiter-post-check:
    max-calls: 6                  # on-demand per-check cap
    ttl-days: 7
    ai-verification-enabled: true
    recency-window: PAST_MONTH    # PAST_24H | PAST_WEEK | PAST_MONTH
    automated:                    # Phase 5: post-pipeline-tick batch (async dispatch, no cron)
      enabled: true
      top-n: 10                   # per tick; not-yet-checked jobs from today's KEEP set
      daily-call-budget: 40       # daily ceiling for automated mode (~2 calls/job × top-n, 2× headroom)
      slim-mode: true             # single best query variant per job
```

**Digest read endpoint** (`Phase 6`): `POST /api/linkedin/recruiter-post-check/batch-read` (cache-only, zero LinkedIn calls), body `{ "jobUrls": [...] }`, returns `List<RecruiterPostCheckResult>` (fresh cached rows only; absent URLs omitted). Exposed under the same `@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled")` gate as the rest of `LinkedInController`.
