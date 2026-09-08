# Plan: Recruiter Post Detection

## Overview

Given a link to any job opening, determine whether a recruiter or hiring-team member posted about that specific opening on LinkedIn, with high accuracy. Runs in two modes: (1) **automated** — after the daily digest is generated, a background job checks the top N highest-scored filter-passing jobs (slim call profile, daily budget cap) so digest cards show recruiter-poster details asynchronously; (2) **on-demand** — MCP tool + REST endpoint for arbitrary job URLs (~4-6 calls). Both modes share the same multi-signal verification pipeline (link match, company/title text match, author role classification, recency, AI tiebreaker). Verified matches (HIGH/MEDIUM) persist as `OutreachContact` so the existing connect/message flow works immediately. Digest UX: recruiter info appears inline on the job card when found; silent when not found or not yet checked.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| API | Java 21, Spring Boot 3.3.5 (new service in `dev.jobhunter.linkedin`) |
| LinkedIn access | Existing `HttpMcpClient` → linkedin-mcp sidecar (`search_posts`, `get_job_details`, `get_person_profile`, `search_people`) |
| Rate limiting | Existing `LinkedInRateLimiter` (SEARCH/PROFILE buckets) |
| Job resolution | `JobPostingRepository.findByApplyUrl*` (DB), `get_job_details` (LinkedIn URLs), `FetchStrategy`/`StrategyRegistry` (ATS URLs) |
| AI tiebreaker | Existing AI provider stack (Anthropic/OpenAI) for borderline post classification |
| Storage | PostgreSQL 16 + Liquibase (new `recruiter_post_check` cache table; reuse `outreach_contact`) |
| MCP tool | TypeScript, jobhunter-mcp (`check_recruiter_post`), calls new REST endpoint |
| Testing | JUnit 5 + Mockito + WireMock (API), Vitest (MCP) |

## Testing Strategy

- Unit: query builder variants, signal scorer (each signal independently), confidence verdict mapping, author role classifier, job-context resolution fallbacks (mocked `HttpMcpClient`, repositories, strategies)
- Integration: WireMock simulating `search_posts`/`get_job_details` JSON-RPC responses through the REST endpoint; cache TTL behavior; contact persistence on HIGH/MEDIUM
- E2E: manual verification with live linkedin-mcp sidecar on one Greenhouse URL + one LinkedIn URL
- Done when: `check_recruiter_post` returns verdict + evidence for arbitrary job URLs, repeat checks within TTL hit cache (0 LinkedIn calls), HIGH/MEDIUM matches create `OutreachContact` rows, all unit/integration tests pass

## Phases

### Phase 1: Job Context Resolution

- Step 1: Define `JobContext` (title, company, location, postedDate, applyUrl, source AtsType, linkedinJobId)
- Step 2: DB lookup by apply URL (`findFirstByApplyUrl` / `findFirstByApplyUrlStartingWith`) → `JobContext`
- Step 3: LinkedIn URL path → `get_job_details` → `JobContext`
- Step 4: ATS URL path → detect `AtsType`, run matching `FetchStrategy` for single job → `JobContext`
- Step 5: Graceful failure: unresolvable URL → error result (no LinkedIn calls wasted)

### Phase 2: Post Search + Verification

- Step 1: Query builder: 2-3 `search_posts` variants (e.g. `"{company}" "{title}"`, `"{company}" hiring "{role}"`) with recency filter (past-week/past-month)
- Step 2: Candidate collection + dedup by post URL
- Step 3: Signal scorer: link match (strongest), company-name match, fuzzy title match, author role classification (recruiter/talent/hiring/founder/eng-lead keywords), recency window vs job postedDate
- Step 4: Confidence verdict: HIGH (link match OR company+title+recruiter-author), MEDIUM (company+title, author unclear), UNCERTAIN, NOT_FOUND
- Step 5: AI tiebreaker for MEDIUM/UNCERTAIN candidates: LLM classifies "does this post refer to this specific opening?" (configurable, on by default)
- Step 6: Budget enforcement: max 4-6 LinkedIn calls per check, abort with partial results if exceeded

### Phase 3: Persistence

- Step 1: `RecruiterPostCheck` entity + Liquibase changeset (jobUrl unique, verdict, confidence, result JSONB, checkedAt, expiresAt; TTL 7 days)
- Step 2: Cache read on entry (fresh check → return cached verdict, 0 LinkedIn calls)
- Step 3: On HIGH/MEDIUM: create/link `OutreachContact` (company, author name/title/LinkedIn URL, notes = post URL + snippet + matched job URL); dedupe by LinkedIn URL
- Step 4: Re-check force flag to bypass cache

### Phase 4: Exposure

- Step 1: REST `POST /api/linkedin/recruiter-post-check` (body: url, force?) in `LinkedInController` style, `@ConditionalOnProperty` gated
- Step 2: MCP tool `check_recruiter_post` in jobhunter-mcp (input: url; output: verdict, confidence, matched posts with author/post URL/snippets, contactId if saved, callsUsed)
- Step 3: API client method + Vitest tests in mcp-server
- Step 4: Unit + WireMock integration tests for API side

### Phase 5: Automated Pipeline Integration (post-pipeline-tick)

- Step 1: `RecruiterPostDetectionScheduler`: dispatched asynchronously by `PipelineScheduler` after each 4-hourly tick's final scoring pass (no separate cron; own concurrency guard)
- Step 2: Selects top N not-yet-checked jobs from today's KEEP set ordered by OpportunityScore (N configurable, default 10; re-queried live at run time)
- Step 3: Slim mode for automated checks: single best query variant per job (~2 calls), daily call budget cap; skip jobs already cached fresh
- Step 4: Config in application.yaml: `recruiter-post-check.automated.{enabled, top-n, daily-call-budget, slim-mode}`
- Step 5: Results persist via same Phase 3 path (cache + OutreachContact); digest cards pick results up on next load/refresh
- Step 6: Scheduler tests: top-N selection, budget cap, skip-cached, disabled flag, concurrency guard, pipeline non-interference

### Phase 6: Digest UX (Dashboard)

- Step 1: DailyDigest job cards fetch recruiter-post check results for visible jobs (existing check endpoint, cached reads are cheap)
- Step 2: When verdict HIGH/MEDIUM: render recruiter block on card (name, title, post snippet, "posted Xd ago", link to post) + existing Connect/Message actions via contactId
- Step 3: Silent on no-match / not-yet-checked / UNCERTAIN (no visual noise)
- Step 4: Vitest tests for card states (found / not-found-silent / loading)

## Risks/Edge cases

- LinkedIn `search_posts` keyword noise (aggregators, unrelated roles): mitigated by multi-signal scoring; keyword-only matches never exceed UNCERTAIN
- Company name ambiguity (e.g. "Nova" matches many companies): require exact normalized company token match + author company cross-check
- Job title variants ("Senior Java Engineer" vs "Senior Java Backend Developer"): fuzzy title matching + AI tiebreaker
- Poster deleted post / private post between check and outreach: store post URL + snippet at check time; treat stale cache conservatively
- Rate-limit exhaustion mid-check: budget counter aborts with partial results + verdict UNCERTAIN
- Automated batch vs on-demand contention: daily budget shared/reserved; automated scheduler yields to rate limiter, resumes next run
- Session invalid: fail fast with clear error before any calls (existing `isSessionValid`); automated run skips and retries next cycle
- URL not resolvable to any job (dead link, auth-walled ATS): return explicit `UNRESOLVED` error, no LinkedIn calls
- Partial digest UX (async results arrive after page load): silent no-match means no flicker; recruiter block appears on next refresh/poll
- LinkedIn ToS: automated mode is read-only, budget-capped (top N × slim profile), within existing rate-limit buckets; on-demand remains user-initiated
