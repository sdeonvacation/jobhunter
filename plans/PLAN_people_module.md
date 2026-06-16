# Plan: People Module

## Overview

Transform JobHunter from a job discovery platform into a job acquisition operating system by adding a People vertical — automatic contact discovery (from ATS pages + LinkedIn), relationship CRM, AI-powered outreach with referral prioritization, and pipeline analytics with daily action queue. Goal: turn discovered jobs into interviews via people, not just applications.

## Tech Stack

- Java 21 / Spring Boot 3.3.5 (API extensions)
- Liquibase (schema migrations)
- React 18 / Vite / Tailwind (new dashboard pages)
- Existing AI providers (Anthropic/OpenAI) for message generation
- Existing LinkedIn MCP for people search/connect
- PostgreSQL 16 (JSONB for profile data)

## Testing Strategy

- Unit: Scorer logic (contact priority, warmth), message generation, poster extraction, relationship state machine + event sourcing
- Integration: LinkedIn MCP round-trip (WireMock), pipeline aggregation queries, poster parsing from real ATS HTML
- Done when: Dashboard shows auto-discovered contacts per company, ranked by priority score, with AI-generated outreach and daily action queue showing actionScore (impact × urgency) per action

## Phases

### Phase 1: People Discovery

Auto-discover contacts from two sources: ATS job pages (already crawled) and LinkedIn search. Poster extraction is the highest-ROI feature — gets you recruiter + job link at crawl time, skipping LinkedIn search entirely.

- Step 1: **Poster Extraction Subsystem** — dedicated component (not buried in extractors). For each ATS type, extract recruiter/HM name + title + LinkedIn URL from job page HTML. Fields EXIST on JobPosting (`posterName`, `posterTitle`, `posterLinkedinUrl`), currently unpopulated. Auto-create `OutreachContact` records and set `posterContactId` FK. Track extraction rate per ATS type. This is a first-class subsystem because it produces contacts at zero additional API cost.
- Step 2: **Extend OutreachContact** — add `seniority` (RECRUITER/MANAGER/DIRECTOR/STAFF/SENIOR/IC), `discoveredVia` (JOB_POSTER/LINKEDIN_SEARCH/MANUAL), `location`, `techStack[]` (JSONB)
- Step 3: **LinkedIn Discovery Service** — for ACTIVE companies where poster extraction yielded nothing, call MCP `find_contacts` with configurable title keywords. Scheduler: daily for top-priority companies, weekly for others. Respect shared daily quota.
- Step 4: **Contact Priority Score** — single ranking number combining:
  - `interviewGenerationWeight`: RECRUITER=95, MANAGER=90, SENIOR=75, STAFF=70, PRINCIPAL=65 (configurable in profile.yaml). These are WEIGHTS not probabilities — no false precision.
  - `warmthScore`: shared employer history (30%), same country/migration path (25%), same university (15%), same tech stack (15%), mutual connections/interactions (15%). Captures WHY someone would respond.
  - Final: `contactPriorityScore = 0.6 * interviewGenerationWeight + 0.4 * warmthScore` (normalized 0-100)
  - Store all three values. UI sorts by composite. User sees warmth indicator separately ("warm intro possible" vs "cold outreach").
- Step 5: **ContactDiscoveryRun** entity — audit log (company_id, source, contactsFound, contactsNew, runAt)
- Step 6: **Deduplication** — match by linkedinUrl (already UNIQUE constraint). Merge if same person found via different sources (poster + LinkedIn search → keep both discovery sources).

### Phase 2: Relationship CRM

Track interaction state via events, query by status.

- Step 1: **Relationship entity** — `(id, contact_id, status, lastContactAt, lastReplyAt, responseRate, referralRequested, referred, interviewObtained, referredByContactId, notes)`. Status enum: `DISCOVERED | CONTACTED | REPLIED | ENGAGED | REFERRED | INTERVIEW_OBTAINED | GHOSTED | COLD`. Status is derived from events but stored for fast querying.
- Step 2: **RelationshipEvent entity** — event log `(id, relationship_id, eventType, occurredAt, metadata JSONB)`. Event types: `CONTACT_DISCOVERED | MESSAGE_SENT | REPLIED | CALL_BOOKED | REFERRAL_REQUESTED | REFERRAL_GIVEN | INTERVIEW_OBTAINED | GHOSTED_AUTO | STATUS_OVERRIDE`. Enables queries like "who replied but never got follow-up" (has REPLIED event, no subsequent MESSAGE_SENT).
- Step 3: **OutreachMessage entity** — message history `(id, contact_id, direction IN/OUT, channel LINKEDIN/EMAIL, messageType: INFO_CHAT/TECH_DISCUSSION/REFERRAL/FOLLOWUP/RECRUITER, content, sentAt, replied, repliedAt)`
- Step 4: **InterviewSource on Application** — new enum field: `APPLICATION | RECRUITER | REFERRAL | NETWORKING | EVENT`. Track how each interview was obtained. Six months of data → evidence of what works.
- Step 5: **CRM Dashboard page** (`/people`) — contacts grouped by relationship status, sortable by contactPriorityScore. Filters: company, seniority, status, connection state. Warmth indicator (warm/cold badge).
- Step 6: **Contact detail panel** — profile info, relationship event timeline, message history, linked jobs (via posterContactId), referral chain (referredByContactId)
- Step 7: **Job card enhancement** — badge showing "X contacts · Y connected" per company. Click drills into relevant contacts.
- Step 8: **Company page contacts tab** — seniority breakdown, top-scored contacts, relationship status distribution

### Phase 3: AI Outreach

Context-aware message generation with variant types.

- Step 1: **Message variant templates** in profile.yaml — `outreach.templates.{info-chat, tech-discussion, referral-ask, follow-up, recruiter-pitch}` with system prompt + context schema per type
- Step 2: **OutreachMessageGenerator** service — input: (contact profile, target job, user resume, variant type, relationship history) → personalized message. Fundamentally different prompts per variant.
- Step 3: **API endpoint** — `POST /api/contacts/{id}/generate-message {type, jobId?}` → returns draft
- Step 4: **Dashboard integration** — "Generate" button on contact card with type selector. Preview/edit/send flow. Stores in OutreachMessage with `templateUsed`.

### Phase 4: Pipeline Analytics + Opportunity Queue

Funnel intelligence and daily prioritized actions with urgency.

- Step 1: **Expanded funnel tracking** — Applications → Recruiter Screen → Technical → Final → Offer. Conversion loss per stage. `InterviewSource` attribution shows which channel produces interviews.
- Step 2: **Funnel aggregation endpoint** — `GET /api/pipeline/funnel` → stage counts, conversion rates, avg time-between-stages, breakdown by InterviewSource
- Step 3: **"Where am I failing"** — AI analysis of funnel shape. Top-of-funnel problem (low interview rate) vs. interviewing problem (low offer rate). Evidence-based, not opinion.
- Step 4: **Opportunity Queue with urgency** — `GET /api/actions/today` → daily prioritized actions. Each action has:
  - `impactScore`: estimated interview-generation value (based on contact priority, job match, channel effectiveness)
  - `urgencyScore`: time-based decay (e.g., "last reply 6 days ago, expires in 2 days" = high urgency; "new contact discovered today" = low urgency)
  - `actionScore = impactScore × urgencyScore` (final sort key)
  - `reason`: why this action, why now ("Recruiter replied 6 days ago — follow-up window closing")
  - `expiresIn`: human-readable deadline
- Step 5: **Dashboard "Today" page** — action queue sorted by actionScore. Each card shows action type, person/job, impactScore, urgency indicator, deadline. Not "top jobs" — "top actions".
- Step 6: **Effectiveness tracking** (moved here — needs data volume) — correlate message variants + templates with: connection acceptance rate, reply rate, interview generation rate. Requires hundreds of interactions for statistical validity.

### Phase 5: Company Intelligence (Raw Facts)

Store facts. No premature scoring.

- Step 1: **Extend Company** — populate existing fields (industry, employeeCount, specialties) via LinkedIn MCP enrichment
- Step 2: **Add raw fact fields** — `hiringVelocity` (jobs posted last 30d), `employeeGrowth`, `fundingStage`
- Step 3: **VisaFriendliness** — enum (UNKNOWN/LOW/MEDIUM/HIGH). NOT AI-inferred. Raw signals: `hasSponsoredBefore`, `englishSpeaking`, `internationalWorkforce`. AI can suggest → user confirms. No hallucinated data.
- Step 4: **Company enrichment scheduler** — periodic refresh for ACTIVE companies via LinkedIn MCP
- Step 5: **Surface in dashboard** — company detail page shows raw facts. No composite score. User judges.

## Risks/Edge cases

- **LinkedIn rate limits**: Shared daily quota across manual + auto discovery + outreach. Scheduler checks remaining budget before each action. Priority queue (highest contactPriorityScore first).
- **Stale contacts**: People change jobs. Re-validate contacts >90 days old; mark STALE if profile shows different company.
- **GDPR**: Extend existing `recruiterDataExpiresAt` pattern to all contact data. Configurable retention + purge scheduler.
- **Job poster parsing quality**: ATS pages vary. Per-extractor enrichment; track extraction rate per ATS type. Some will yield 80%+ posters, some 0%.
- **Relationship state drift**: User interacts outside system. Sync button + manual status override + "last known" semantics.
- **GHOSTED detection**: Auto-transition CONTACTED → GHOSTED after configurable threshold (default 14 days no reply).
- **Score accuracy**: Weights are heuristics initially, not probabilities. No "95% chance" language in UI — use ranks/tiers instead. Refine with actual outcome data over time (Phase 4 Step 6).
- **Scope**: Phase 1+2 = MVP (people visible, relationships tracked). Phase 3 = high-value add. Phase 4+5 = polish. Each independently shippable.

## Future Evolution

- **Agency Module**: Treat recruitment agencies (Hays, Robert Half, Michael Page, Darwin, Optimus Search) as first-class entities alongside companies. Entity: `RecruitmentAgency` with recruiter contacts, roles sent, interview conversion. Major path to work permits in Germany.
- **Composite company score**: Only after 500+ companies with outcome data. Evidence-based, not premature.
