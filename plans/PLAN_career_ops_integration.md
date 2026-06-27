# Plan: Career-Ops Integration

## Overview

Integrate career-ops's deep evaluation capabilities into jobhunter as a downstream evaluation engine. JobHunter discovers and keyword-scores jobs (existing). For shortlisted jobs, a new AI-driven evaluation pipeline produces structured assessments (blocks A-G), cover letters, interview prep, ghost detection, pattern analysis, and follow-up cadence. All stored in PostgreSQL, exposed via REST + MCP, rendered in dashboard.

## Tech Stack

- Java 21 + Spring Boot 3.3.5 (existing backend)
- PostgreSQL 16 (existing DB)
- Anthropic/OpenAI via existing `AiProvider` interface
- React 18 + Vite (existing dashboard)
- MCP server TypeScript (existing)
- Playwright (new - for liveness checks)
- Liquibase (migrations)
- Quartz (scheduler - existing)

## Testing Strategy

- Unit: Service logic (evaluation scoring, cadence calculation, pattern aggregation) with mocked AI
- Integration: Full evaluation flow with Testcontainers + WireMock for AI provider
- Done when: All 6 features have passing unit tests + 1 integration test per feature; MCP tools callable; dashboard pages render

## Phases

### Phase 1: Deep Evaluation Engine (Core)

- Step 1: New `JobEvaluation` entity (1:1 with JobPosting) storing block results, overall score (1-5), archetype, legitimacy tier
- Step 2: New `EvaluationService` implementing 7-block evaluation via AI provider (structured extraction per block)
- Step 3: New REST endpoint `POST /api/jobs/{id}/evaluate` triggering deep eval
- Step 4: New MCP tool `evaluate_job` (accepts job ID or URL, returns evaluation summary)
- Step 5: Evaluation results page/section in dashboard (expandable blocks A-G)
- Step 6: Profile source-of-truth: read from existing `profile.yaml` + new `cv.md` equivalent for AI prompts

### Phase 2: Enhanced Cover Letter

- Step 1: Extend existing `CoverLetterService` with career-ops's 10-step interactive flow
- Step 2: New `CoverLetter` entity persisting generated letters (linked to job)
- Step 3: Research step: company context via web search (optional, user-triggered)
- Step 4: Keyword mirroring from job description
- Step 5: New MCP tool `generate_cover_letter` with tone/focus params + interactive angle collection
- Step 6: Dashboard: cover letter viewer/editor per job

### Phase 3: Ghost Detection / Liveness

- Step 1: New `LivenessStatus` enum (ACTIVE, EXPIRED, UNCERTAIN) + column on JobPosting
- Step 2: New `LivenessCheckService` — HTTP-first (ATS API check for Greenhouse/Lever), Playwright fallback
- Step 3: Integrate into crawl pipeline: check liveness before scoring newly discovered jobs
- Step 4: Periodic re-check for applied jobs (Quartz scheduler, configurable interval)
- Step 5: New MCP tool `check_job_liveness` 
- Step 6: Dashboard: badge/indicator on job cards; filter for expired jobs

### Phase 4: Interview Prep + STAR Bank

- Step 1: New `InterviewStory` entity (situation, task, action, result, reflection, tags/skills)
- Step 2: New `InterviewPrep` entity (1:1 with JobPosting) storing mapped stories + talking points
- Step 3: New `InterviewPrepService` — generates STAR stories mapped to JD requirements via AI
- Step 4: Story bank accumulation: stories extracted from evaluations persist across jobs
- Step 5: New REST endpoints + MCP tool `prepare_interview`
- Step 6: Dashboard: interview prep page per job (stories, Q&A, company research)

### Phase 5: Pattern Analysis

- Step 1: New `PatternAnalysisService` — aggregates from application history in DB
- Step 2: Funnel metrics: evaluated → applied → responded → interview → offer (conversion rates)
- Step 3: Blocker analysis: common rejection reasons, tech stack gaps
- Step 4: Score threshold recommendation: minimum score where positive outcomes start
- Step 5: New REST endpoint `GET /api/analytics/patterns` + MCP tool `get_application_patterns`
- Step 6: Dashboard: analytics page with funnel chart, archetype breakdown, recommendations

### Phase 6: Follow-up Cadence

- Step 1: New `FollowUp` entity (job_id, scheduled_date, sent_date, count, status)
- Step 2: New `FollowUpCadenceService` — calculates next follow-up based on configurable rules
- Step 3: Cadence config in `profile.yaml` (intervals per status, max attempts)
- Step 4: Quartz job: daily check for overdue follow-ups, surface in MCP + dashboard
- Step 5: New MCP tool `get_followup_schedule` (returns due/overdue/upcoming)
- Step 6: Dashboard: follow-up timeline + urgency indicators on applied jobs

## Risks/Edge cases

- **AI cost**: Deep evaluation uses significant tokens per job. Mitigation: only evaluate on explicit user action (never batch-auto), cache evaluations, allow partial block evaluation.
- **Liveness false positives**: Cloudflare/WAF may block Playwright checks. Mitigation: HTTP-first approach, headed-browser fallback, exponential backoff, mark as UNCERTAIN not EXPIRED.
- **Profile drift**: Two profile sources (profile.yaml for scoring, cv.md for AI evaluation). Mitigation: single `profile.yaml` remains source of truth for config; add `cv-content` field or reference file path for AI prompts.
- **Stale evaluations**: Job descriptions change, evaluations become outdated. Mitigation: store evaluation timestamp, flag if job re-crawled with different description fingerprint.
- **Story bank bloat**: Accumulated stories may become unwieldy. Mitigation: tag-based retrieval, relevance scoring, user curation via dashboard.
- **Rate limits**: AI provider rate limits during batch evaluation. Mitigation: queue-based processing with configurable concurrency, respect provider limits.
