# HLD: Career-Ops Integration

## Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Java 21 | Existing backend runtime |
| Framework | Spring Boot 3.3.5 | Existing service framework |
| Database | PostgreSQL 16 | All entity persistence + JSONB for structured blocks |
| Migrations | Liquibase | Schema evolution |
| AI | Anthropic/OpenAI via AiProvider | Structured extraction (blocks A-G) + generation (cover letters) |
| Scheduler | Quartz | Follow-up cadence checks, liveness re-checks |
| Browser | Playwright (Java) | Liveness fallback for JavaScript-rendered ATS pages |
| Frontend | React 18 + Vite + Tailwind | Dashboard pages for evaluation, prep, analytics |
| MCP | TypeScript + zod | 5 new tools for AI agent access |

## Components

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| EvaluationService | Orchestrate 7-block AI evaluation, compute overall score | AiProvider, PersonalProfileLoader, JobPostingRepository |
| EvaluationController | REST endpoint for triggering/reading evaluations | EvaluationService |
| CoverLetterGenerationService | Enhanced 10-step cover letter flow with persistence | AiProvider, PersonalProfileLoader, JobPostingRepository |
| CoverLetterController | REST endpoints for cover letter CRUD | CoverLetterGenerationService |
| LivenessCheckService | HTTP-first + Playwright fallback liveness checks | WebClient, PlaywrightRunner, JobPostingRepository |
| LivenessScheduler | Periodic re-check for applied jobs | LivenessCheckService, Quartz |
| InterviewPrepService | Generate STAR stories mapped to JD, manage story bank | AiProvider, InterviewStoryRepository |
| InterviewPrepController | REST endpoints for interview prep + story bank | InterviewPrepService |
| PatternAnalysisService | Aggregate funnel metrics, blockers, threshold recommendations | ApplicationRepository, JobOutcomeRepository |
| AnalyticsController | REST endpoint for pattern analytics | PatternAnalysisService |
| FollowUpCadenceService | Calculate next follow-up dates, surface overdue items | FollowUpRepository, PersonalProfileLoader |
| FollowUpScheduler | Daily check for overdue follow-ups | FollowUpCadenceService, Quartz |
| FollowUpController | REST endpoints for follow-up schedule | FollowUpCadenceService |
| PlaywrightRunner | Thin wrapper around Playwright Java for browser-based checks | Playwright dependency |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         MCP Server (TS)                              │
│  evaluate_job │ generate_cover_letter │ check_job_liveness          │
│  prepare_interview │ get_application_patterns │ get_followup_schedule│
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTP (localhost:8080/api/*)
┌──────────────────────────────▼──────────────────────────────────────┐
│                     Spring Boot API Layer                            │
│                                                                     │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐              │
│  │ Evaluation   │ │ CoverLetter  │ │ InterviewPrep  │              │
│  │ Controller   │ │ Controller   │ │ Controller     │              │
│  └──────┬───────┘ └──────┬───────┘ └───────┬────────┘              │
│         │                │                  │                       │
│  ┌──────▼───────┐ ┌──────▼───────┐ ┌───────▼────────┐             │
│  │ Evaluation   │ │ CoverLetter  │ │ InterviewPrep  │             │
│  │ Service      │ │ Generation   │ │ Service        │             │
│  │              │ │ Service      │ │                │             │
│  └──────┬───────┘ └──────┬───────┘ └───────┬────────┘             │
│         │                │                  │                       │
│  ┌──────▼────────────────▼──────────────────▼───────┐              │
│  │              AiProvider Interface                  │              │
│  │     (AnthropicProvider / OpenAiProvider)           │              │
│  └───────────────────────────────────────────────────┘              │
│                                                                     │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐              │
│  │ Liveness     │ │ Pattern      │ │ FollowUp       │              │
│  │ Controller   │ │ Analytics    │ │ Controller     │              │
│  └──────┬───────┘ │ Controller   │ └───────┬────────┘              │
│         │         └──────┬───────┘         │                       │
│  ┌──────▼───────┐ ┌──────▼───────┐ ┌───────▼────────┐             │
│  │ Liveness     │ │ Pattern      │ │ FollowUp       │             │
│  │ CheckService │ │ Analysis     │ │ Cadence        │             │
│  │              │ │ Service      │ │ Service        │             │
│  └──┬───────┬───┘ └──────────────┘ └────────────────┘             │
│     │       │                                                       │
│  ┌──▼──┐ ┌──▼───────────┐   ┌────────────────────────┐            │
│  │ HTTP│ │ Playwright   │   │ Quartz Schedulers       │            │
│  │ ATS │ │ Runner       │   │ (Liveness + FollowUp)   │            │
│  │Check│ │ (fallback)   │   └────────────────────────┘            │
│  └─────┘ └──────────────┘                                          │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ JPA/Hibernate
┌──────────────────────────────▼──────────────────────────────────────┐
│                      PostgreSQL 16                                   │
│  job_evaluation │ cover_letter │ interview_story │ interview_prep   │
│  follow_up │ job_posting (liveness_status column)                   │
└─────────────────────────────────────────────────────────────────────┘
```

**Description**: User-triggered evaluation flows through REST controllers to service layer. Services use `AiProvider.extract()` for structured output (evaluation blocks, interview stories) and `AiProvider.generate()` for free-form text (cover letters). All results persist in PostgreSQL. Schedulers handle only automated checks (liveness re-check, follow-up reminders). MCP tools are thin HTTP clients calling the same REST endpoints. Dashboard reads persisted data via GET endpoints.

## Interfaces

### EvaluationController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| POST /api/jobs/{id}/evaluate | Path: UUID jobId; Body: `{blocks?: string[]}` | `EvaluationDto` | Triggers AI evaluation (all or subset of blocks A-G), persists result | 404 job not found, 409 evaluation in progress, 503 AI unavailable |
| GET /api/jobs/{id}/evaluation | Path: UUID jobId | `EvaluationDto` | Returns persisted evaluation | 404 job or evaluation not found |
| DELETE /api/jobs/{id}/evaluation | Path: UUID jobId | 204 | Removes evaluation (allows re-evaluation) | 404 not found |

### CoverLetterController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| POST /api/jobs/{id}/cover-letter | Path: UUID jobId; Body: `{tone, focus, angles[]}` | `CoverLetterDto` | Generates enhanced cover letter, persists | 404 job not found, 503 AI unavailable |
| GET /api/jobs/{id}/cover-letter | Path: UUID jobId | `CoverLetterDto` | Returns persisted cover letter | 404 not found |
| PUT /api/jobs/{id}/cover-letter | Path: UUID jobId; Body: `{content}` | `CoverLetterDto` | User edits persisted letter | 404 not found |
| DELETE /api/jobs/{id}/cover-letter | Path: UUID jobId | 204 | Removes cover letter | 404 not found |

### LivenessController (under /api/jobs)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| POST /api/jobs/{id}/liveness-check | Path: UUID jobId | `LivenessResultDto` | Triggers on-demand liveness check | 404 job not found, 503 check failed |
| GET /api/jobs/{id}/liveness | Path: UUID jobId | `LivenessResultDto` | Returns last check result | 404 not found |

### InterviewPrepController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| POST /api/jobs/{id}/interview-prep | Path: UUID jobId | `InterviewPrepDto` | Generates mapped STAR stories + talking points | 404, 503 |
| GET /api/jobs/{id}/interview-prep | Path: UUID jobId | `InterviewPrepDto` | Returns persisted prep | 404 |
| GET /api/interview-stories | Query: `?tags=java,spring&limit=20` | `List<InterviewStoryDto>` | Lists story bank with filters | - |
| POST /api/interview-stories | Body: `InterviewStoryCreateDto` | `InterviewStoryDto` | Manually add story | 400 validation |
| PUT /api/interview-stories/{id} | Path: UUID; Body: edits | `InterviewStoryDto` | Edit story | 404 |
| DELETE /api/interview-stories/{id} | Path: UUID | 204 | Remove story | 404 |

### AnalyticsController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| GET /api/analytics/patterns | Query: `?since=2024-01-01` | `PatternAnalysisDto` | Computes funnel, blockers, recommendations from DB | - |
| GET /api/analytics/funnel | Query: `?since=` | `FunnelDto` | Conversion rates per stage | - |

### FollowUpController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| GET /api/follow-ups | Query: `?status=OVERDUE&limit=20` | `List<FollowUpDto>` | Lists follow-ups by status | - |
| POST /api/jobs/{id}/follow-up | Path: UUID jobId | `FollowUpDto` | Starts follow-up cadence for applied job | 404, 409 already exists |
| PATCH /api/follow-ups/{id}/sent | Path: UUID followUpId | `FollowUpDto` | Marks follow-up as sent, schedules next | 404 |
| DELETE /api/follow-ups/{id} | Path: UUID | 204 | Cancels follow-up cadence | 404 |

## Data Flow

### Evaluation Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Client (Dashboard/MCP) | POST /api/jobs/{id}/evaluate | EvaluationController |
| 2 | EvaluationController | Validates job exists, checks no in-progress eval | EvaluationService |
| 3 | EvaluationService | Loads job description + profile + CV content | AiProvider |
| 4 | AiProvider | Sequentially extracts blocks A-G as structured JSON | EvaluationService |
| 5 | EvaluationService | Computes overall score (1-5), detects archetype | JobEvaluationRepository |
| 6 | JobEvaluationRepository | Persists JobEvaluation entity | Response |
| 7 | EvaluationController | Returns EvaluationDto | Client |

### Liveness Check Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Client or Scheduler | POST /api/jobs/{id}/liveness-check | LivenessCheckService |
| 2 | LivenessCheckService | Determines ATS type from job's endpoint | HTTP check |
| 3 | HTTP Check | Calls ATS API (Greenhouse/Lever/etc.) for job status | Decision |
| 4 | Decision | If HTTP conclusive → ACTIVE/EXPIRED; if inconclusive → Playwright | PlaywrightRunner |
| 5 | PlaywrightRunner | Loads apply URL in headless browser, checks for expired indicators | LivenessCheckService |
| 6 | LivenessCheckService | Updates JobPosting.livenessStatus + lastLivenessCheck | Response |

### Cover Letter Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Client | POST /api/jobs/{id}/cover-letter with tone/focus/angles | CoverLetterGenerationService |
| 2 | Service | Loads job + skills + profile + evaluation (if exists) | Prompt building |
| 3 | Service | Builds 10-step prompt: keyword extraction → company research → angle selection → draft | AiProvider |
| 4 | AiProvider | generate() produces cover letter text | Service |
| 5 | Service | Persists CoverLetter entity | Response |

**Error Flows**:
- AI provider unavailable → 503 with retry-after hint, no partial state saved
- Job not found → 404, early return before AI call
- AI returns malformed response → AiProviderException caught, 500 with error detail logged
- Playwright timeout → mark as UNCERTAIN (not EXPIRED), log warning
- Duplicate evaluation request → 409, return existing evaluation ID

## Data Model

### New Entities

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| JobEvaluation | id: UUID, roleSummary: jsonb, cvMatch: jsonb, levelStrategy: jsonb, compResearch: jsonb, customizationPlan: jsonb, interviewPlan: jsonb, legitimacy: jsonb, overallScore: int (1-5), archetype: String, legitimacyTier: String, evaluatedAt: Timestamp, descriptionFingerprint: String | @OneToOne JobPosting (job_id FK, unique) | overallScore 1-5, non-null job_id |
| CoverLetter | id: UUID, content: Text, tone: String, focus: String, angles: jsonb, keywordsMirrored: jsonb, version: int, generatedAt: Timestamp, editedAt: Timestamp | @ManyToOne JobPosting (job_id FK) | non-null content |
| InterviewStory | id: UUID, situation: Text, task: Text, action: Text, result: Text, reflection: Text, tags: jsonb, skills: jsonb, sourceJobId: UUID (nullable), createdAt: Timestamp, updatedAt: Timestamp | Optional @ManyToOne JobPosting (source_job_id FK, nullable) | non-null situation+action+result |
| InterviewPrep | id: UUID, talkingPoints: jsonb, mappedStoryIds: jsonb, companyResearch: jsonb, preparedAt: Timestamp | @OneToOne JobPosting (job_id FK, unique) | non-null job_id |
| FollowUp | id: UUID, scheduledDate: LocalDate, sentDate: LocalDate (nullable), count: int, status: FollowUpStatus enum, notes: Text, createdAt: Timestamp, updatedAt: Timestamp | @ManyToOne JobPosting (job_id FK) | status in (PENDING, SENT, OVERDUE, CANCELLED) |

### Modified Entities

| Entity | Changes | Constraints |
|--------|---------|-------------|
| JobPosting | Add: livenessStatus: LivenessStatus enum, lastLivenessCheck: Timestamp, evaluation: OneToOne backref, coverLetters: OneToMany backref, interviewPrep: OneToOne backref | livenessStatus default null (unchecked) |

### New Enums

| Enum | Values | Purpose |
|------|--------|---------|
| LivenessStatus | ACTIVE, EXPIRED, UNCERTAIN | Job posting availability state |
| FollowUpStatus | PENDING, SENT, OVERDUE, CANCELLED | Follow-up lifecycle state |

### JSONB Block Structures (stored in JobEvaluation)

Each block (A-G) is a JSONB column with this contract:

| Block | Key Fields | Purpose |
|-------|-----------|---------|
| A: roleSummary | {title, level, team, techStack[], mustHaves[], niceToHaves[], redFlags[]} | Parsed JD structure |
| B: cvMatch | {overallFit: 1-5, strongMatches[], gaps[], transferable[], narrative: String} | CV alignment |
| C: levelStrategy | {targetLevel, currentLevel, positioningAdvice, riskFactors[]} | Seniority positioning |
| D: compResearch | {companySize, fundingStage, techReputation, glassdoorSignals, salaryRange} | Company intel |
| E: customizationPlan | {resumeTweaks[], coverLetterAngles[], keywordsToMirror[]} | Application strategy |
| F: interviewPlan | {likelyQuestions[], starStorySuggestions[], technicalTopics[], prepPriority[]} | Prep roadmap |
| G: legitimacy | {tier: "GREEN"/"AMBER"/"RED", signals[], concerns[], confidence: 1-5} | Ghost/scam detection |

## MCP Tool Definitions

### evaluate_job

```typescript
inputSchema: z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or posting URL'),
  blocks: z.array(z.enum(['A','B','C','D','E','F','G'])).optional()
    .describe('Subset of blocks to evaluate. Omit for full evaluation.')
})
// Returns: Formatted evaluation summary with scores and key findings
```

### generate_cover_letter

```typescript
inputSchema: z.object({
  job_id: z.string().describe('Job UUID, short ID, or URL'),
  tone: z.enum(['professional','enthusiastic','conversational']).default('professional'),
  focus: z.string().optional().describe('Specific angle or experience to emphasize'),
  angles: z.array(z.string()).optional().describe('Multiple angles to weave in')
})
// Returns: Generated cover letter text
```

### check_job_liveness

```typescript
inputSchema: z.object({
  job_id: z.string().describe('Job UUID, short ID, or URL')
})
// Returns: {status: ACTIVE|EXPIRED|UNCERTAIN, checkedAt, details}
```

### prepare_interview

```typescript
inputSchema: z.object({
  job_id: z.string().describe('Job UUID, short ID, or URL')
})
// Returns: Talking points, mapped STAR stories, likely questions
```

### get_application_patterns

```typescript
inputSchema: z.object({
  since: z.string().optional().describe('ISO date to filter from (default: 90 days ago)')
})
// Returns: Funnel metrics, blocker analysis, score threshold recommendation
```

### get_followup_schedule

```typescript
inputSchema: z.object({
  status: z.enum(['OVERDUE','PENDING','ALL']).default('OVERDUE')
    .describe('Filter by follow-up status'),
  limit: z.number().default(10)
})
// Returns: List of follow-ups with job title, company, due date, count
```

## AI Prompt Strategy

### Block Evaluation (Structured Extraction)

Each block uses `aiProvider.extract(systemPrompt, content, BlockResult.class)`:

| Block | System Prompt Strategy | Input Content | Output Type |
|-------|----------------------|---------------|-------------|
| A: Role Summary | "Parse this JD into structured components. Extract role level, tech stack, requirements." | Job description text | `RoleSummaryBlock.class` |
| B: CV Match | "Compare candidate profile against JD. Rate fit 1-5. Identify strong matches, gaps, transferable skills." | JD + profile.yaml skills + cv-content | `CvMatchBlock.class` |
| C: Level Strategy | "Assess seniority positioning. Current: {profile.years}yr {profile.title}. Advise on positioning." | JD + profile summary | `LevelStrategyBlock.class` |
| D: Comp Research | "Research this company based on the JD signals. Infer size, stage, tech reputation." | JD + company name + available metadata | `CompResearchBlock.class` |
| E: Customization Plan | "Create application customization plan. What resume tweaks, cover letter angles, keywords to mirror." | JD + CV match results (block B) | `CustomizationPlanBlock.class` |
| F: Interview Plan | "Generate likely interview questions and STAR story suggestions based on this role." | JD + profile skills + role summary (block A) | `InterviewPlanBlock.class` |
| G: Legitimacy | "Assess posting legitimacy. Look for red flags: vague requirements, unrealistic scope, reposted frequently." | JD + posting metadata (date, company info) | `LegitimacyBlock.class` |

**Sequencing**: Blocks A and D run first (independent). B depends on A. C depends on A+B. E depends on B. F depends on A+B. G is independent. Total: 3 sequential AI calls minimum with batching.

### Overall Score Computation

```
overallScore = weighted_average(
  cvMatch.overallFit * 0.35,
  legitimacy.confidence * 0.20,
  levelStrategy.fit * 0.20,
  compResearch.attractiveness * 0.15,
  customizationPlan.effort_inverse * 0.10
)
// Rounded to 1-5 scale
```

### Archetype Detection

Derived from block A + B patterns:
- **Perfect Fit**: cvMatch >= 4, all mustHaves matched
- **Growth Stretch**: cvMatch 3, gaps are learnable, level slightly above
- **Lateral Move**: cvMatch >= 4, same level, different company
- **Long Shot**: cvMatch <= 2, significant gaps
- **Red Flag**: legitimacy tier RED

### Cover Letter 10-Step Flow

1. Extract keywords from JD (from block A or fresh parse)
2. Load profile skills + CV content
3. Load evaluation block E (customization plan) if exists
4. Identify 3 strongest matching experiences
5. Research company context (from block D or fresh)
6. Select tone + angles (from user input)
7. Build structured prompt with all context
8. Generate via `aiProvider.generate()`
9. Post-process: verify keyword mirroring, check length
10. Persist with metadata (tone, focus, keywords mirrored)

## Dashboard Component Tree

```
src/
├── pages/
│   ├── Evaluate.tsx          # Job evaluation detail page (blocks A-G expandable)
│   ├── InterviewPrep.tsx     # Interview prep page per job
│   ├── StoryBank.tsx         # STAR story bank management
│   ├── Analytics.tsx         # Pattern analysis + funnel chart
│   └── FollowUps.tsx         # Follow-up timeline + urgency
├── components/
│   ├── EvaluationPanel.tsx   # Inline expandable eval summary (for JobCard)
│   ├── EvalBlockCard.tsx     # Single block (A-G) display card
│   ├── EvalScoreBadge.tsx    # 1-5 score with color + archetype label
│   ├── CoverLetterEditor.tsx # Cover letter viewer/editor with tone selector
│   ├── LivenessBadge.tsx     # ACTIVE/EXPIRED/UNCERTAIN indicator
│   ├── StoryCard.tsx         # Single STAR story display
│   ├── FunnelChart.tsx       # Application funnel visualization
│   ├── FollowUpTimeline.tsx  # Timeline with urgency indicators
│   └── FollowUpBadge.tsx     # Overdue/pending indicator on job cards
```

### Integration Points with Existing Components

| Existing Component | New Addition |
|-------------------|--------------|
| JobCard.tsx | Add LivenessBadge, EvalScoreBadge (if evaluated), FollowUpBadge (if applied) |
| Applied.tsx page | Add follow-up status column, liveness indicators |
| Navigation.tsx | Add "Analytics" and "Story Bank" nav items |
| Jobs.tsx page | Add "Evaluate" action button, liveness filter dropdown |

## Liquibase Migration Plan

### 010-career-ops-evaluation.sql

```sql
-- JobEvaluation entity
CREATE TABLE job_evaluation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL UNIQUE REFERENCES job_posting(id) ON DELETE CASCADE,
    role_summary JSONB,
    cv_match JSONB,
    level_strategy JSONB,
    comp_research JSONB,
    customization_plan JSONB,
    interview_plan JSONB,
    legitimacy JSONB,
    overall_score INTEGER NOT NULL CHECK (overall_score BETWEEN 1 AND 5),
    archetype VARCHAR(50),
    legitimacy_tier VARCHAR(10),
    description_fingerprint VARCHAR(64),
    evaluated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_job_evaluation_job_id ON job_evaluation(job_id);
CREATE INDEX idx_job_evaluation_score ON job_evaluation(overall_score DESC);
```

### 011-career-ops-cover-letter.sql

```sql
-- CoverLetter entity
CREATE TABLE cover_letter (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    tone VARCHAR(30) NOT NULL DEFAULT 'professional',
    focus VARCHAR(255),
    angles JSONB,
    keywords_mirrored JSONB,
    version INTEGER NOT NULL DEFAULT 1,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    edited_at TIMESTAMP
);

CREATE INDEX idx_cover_letter_job_id ON cover_letter(job_id);
```

### 012-career-ops-liveness.sql

```sql
-- Add liveness columns to job_posting
ALTER TABLE job_posting ADD COLUMN liveness_status VARCHAR(20);
ALTER TABLE job_posting ADD COLUMN last_liveness_check TIMESTAMP;

CREATE INDEX idx_job_posting_liveness ON job_posting(liveness_status)
    WHERE liveness_status IS NOT NULL;
```

### 013-career-ops-interview.sql

```sql
-- InterviewStory entity (story bank)
CREATE TABLE interview_story (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    situation TEXT NOT NULL,
    task TEXT,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    reflection TEXT,
    tags JSONB DEFAULT '[]',
    skills JSONB DEFAULT '[]',
    source_job_id UUID REFERENCES job_posting(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_interview_story_tags ON interview_story USING GIN(tags);

-- InterviewPrep entity (per-job)
CREATE TABLE interview_prep (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL UNIQUE REFERENCES job_posting(id) ON DELETE CASCADE,
    talking_points JSONB NOT NULL,
    mapped_story_ids JSONB DEFAULT '[]',
    company_research JSONB,
    prepared_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_interview_prep_job_id ON interview_prep(job_id);
```

### 014-career-ops-followup.sql

```sql
-- FollowUp entity
CREATE TABLE follow_up (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    scheduled_date DATE NOT NULL,
    sent_date DATE,
    count INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_follow_up_job_id ON follow_up(job_id);
CREATE INDEX idx_follow_up_status_date ON follow_up(status, scheduled_date)
    WHERE status IN ('PENDING', 'OVERDUE');
```

## Configuration Additions (profile.yaml)

```yaml
# New section: CV content for AI evaluation prompts
cv-content:
  path: "./cv-content.md"  # Markdown file with structured experience narratives

# New section: evaluation config
evaluation:
  block-timeout-seconds: 30        # Max wait per AI block extraction
  max-description-chars: 8000      # Truncate JD for AI calls
  cache-duration-hours: 168        # Re-evaluate if older than 7 days

# New section: cover letter config
cover-letter:
  max-length-chars: 3000           # Target letter length
  default-tone: "professional"

# New section: liveness config
liveness:
  http-timeout-seconds: 10
  playwright-timeout-seconds: 30
  recheck-interval-hours: 72       # Re-check applied jobs every 3 days
  expired-indicators:              # Text patterns indicating expired posting
    - "no longer accepting"
    - "position has been filled"
    - "this job is closed"

# New section: follow-up cadence
follow-up:
  intervals-days: [3, 7, 14]       # Days after application for each follow-up
  max-attempts: 3
  reminder-time: "09:00"           # When to surface reminders
```

## Sequence Diagrams

### Full Evaluation Flow

```
User          Dashboard       EvalController    EvalService      AiProvider       DB
 │                │                │                │                │             │
 │──[Evaluate]───▶│                │                │                │             │
 │                │──POST /eval───▶│                │                │             │
 │                │                │──checkExists──▶│                │             │
 │                │                │                │──findByJobId──▶│             │
 │                │                │                │◀──null─────────│             │
 │                │                │──evaluate()───▶│                │             │
 │                │                │                │──extract(A)───▶│             │
 │                │                │                │◀──RoleSummary──│             │
 │                │                │                │──extract(B)───▶│             │
 │                │                │                │◀──CvMatch──────│             │
 │                │                │                │──extract(C-G)─▶│             │
 │                │                │                │◀──blocks───────│             │
 │                │                │                │──computeScore()│             │
 │                │                │                │──save()────────────────────▶│
 │                │                │◀──EvalDto──────│                │             │
 │                │◀──200 JSON─────│                │                │             │
 │◀──[Render]─────│                │                │                │             │
```

### Liveness Check Flow

```
Scheduler/User    LivenessService    WebClient     PlaywrightRunner    DB
     │                  │                │                │            │
     │──check(jobId)───▶│                │                │            │
     │                  │──loadJob()─────────────────────────────────▶│
     │                  │◀──JobPosting───────────────────────────────│
     │                  │──httpCheck()───▶│                │            │
     │                  │                │──GET apply_url─▶            │
     │                  │◀──response─────│                │            │
     │                  │                                 │            │
     │            [if inconclusive]                       │            │
     │                  │──browserCheck()────────────────▶│            │
     │                  │                                 │──launch()  │
     │                  │                                 │──navigate()│
     │                  │                                 │──check()   │
     │                  │◀──LivenessResult───────────────│            │
     │                  │──updateStatus()──────────────────────────▶│
     │◀──result─────────│                                            │
```

### Follow-Up Cadence Flow

```
QuartzScheduler    FollowUpService    FollowUpRepo    Dashboard/MCP
      │                  │                 │               │
      │──dailyCheck()───▶│                 │               │
      │                  │──findOverdue()──▶│               │
      │                  │◀──List<FU>──────│               │
      │                  │──markOverdue()──▶│               │
      │                  │                 │               │
      │                  │        [User checks dashboard]  │
      │                  │                 │◀──GET /follow-ups
      │                  │                 │──▶List────────▶│
      │                  │                 │               │
      │                  │       [User marks as sent]      │
      │                  │◀──PATCH /sent───────────────────│
      │                  │──scheduleNext()─▶│               │
      │                  │                 │               │
```

## Error Handling Strategy

| Layer | Strategy | Implementation |
|-------|----------|----------------|
| Controller | Return appropriate HTTP status + error body | `ResponseEntity.status(X).body(ErrorDto)` |
| Service | Catch AI exceptions, wrap in domain exceptions | `EvaluationException`, `LivenessCheckException` |
| AI Provider | Retry with backoff on 429/5xx, fail on 4xx | Existing RetryFilter in WebClient config |
| Playwright | Timeout → UNCERTAIN, crash → log + rethrow | try-catch with configurable timeout |
| Scheduler | Log error, don't crash scheduler thread | Individual item failure doesn't stop batch |
| MCP | Map HTTP errors to MCP error content | `{content: [{type: "text", text: "Error: ..."}]}` |

**Idempotency**: Re-evaluating a job overwrites existing evaluation (DELETE + INSERT semantics via orphanRemoval). Cover letters support versioning (multiple per job). Follow-ups are append-only per job.

**Stale Detection**: `descriptionFingerprint` (SHA-256 of description) stored with evaluation. If job re-crawled with different fingerprint, dashboard shows "evaluation may be outdated" warning.

## Performance Considerations

| Concern | Mitigation |
|---------|-----------|
| AI latency (blocks A-G) | Batch independent blocks (A+D+G parallel), sequential for dependent (B→C→E→F). Total ~15-30s per full eval |
| Playwright cold start | Keep single browser instance alive (lazy-init singleton), reuse across checks |
| Description truncation | Cap JD at 8000 chars for AI calls (configurable) to control token costs |
| N+1 queries | Fetch evaluations eagerly when loading job list pages; use `@EntityGraph` on detail endpoints |
| Story bank growth | GIN index on tags JSONB for efficient tag-based lookup; paginate results |
| Follow-up scheduler | Batch query with single SQL (`WHERE status='PENDING' AND scheduled_date <= CURRENT_DATE`), not per-job |
| Concurrent evaluations | Mark evaluation as "in progress" (optimistic lock or status column) to prevent double-spend |
| DB connection pool | No new connections needed; evaluation is user-triggered (low concurrency) |

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Evaluation trigger | User-only, never automated | Token cost control; ~$0.10-0.30 per full eval | Auto-evaluate top N daily | Higher cost, stale evaluations for all jobs |
| Block storage | JSONB columns per block | Queryable, indexable, no separate tables | Single JSONB blob; separate normalized tables | Slightly more columns vs flexibility; good balance |
| Liveness approach | HTTP-first, Playwright fallback | Fast for 90% of cases (structured ATS APIs) | Always Playwright; external service | Playwright adds dependency but handles edge cases |
| Story persistence | PostgreSQL table | Queryable, taggable, durable | Markdown files; vector DB | Files lack structure; vector DB overkill for <1000 stories |
| Cover letter versioning | Multiple rows per job | Allows comparing drafts, keeping history | Single row overwrite | Slightly more storage, much better UX |
| Follow-up cadence | Profile.yaml config | Consistent with existing config pattern | DB-stored per-job cadence | Less flexible per-job, simpler implementation |
| Playwright dependency | Java Playwright (com.microsoft.playwright) | Same JVM, no IPC overhead | Node.js subprocess; Selenium | Java native = simplest integration |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| AI provider rate limits during evaluation | Evaluation fails mid-block, partial state | Medium | Persist completed blocks individually; allow resume from partial state |
| Playwright Cloudflare blocks | Liveness always returns UNCERTAIN for some ATS | High | Accept UNCERTAIN as valid state; don't auto-expire; headed-browser option |
| Token cost escalation | Monthly AI bill grows with usage | Medium | Dashboard shows token cost per evaluation; daily budget cap in config |
| Stale evaluations misleading user | User acts on outdated evaluation | Low | Fingerprint comparison + "outdated" badge; one-click re-evaluate |
| Story bank quality degradation | AI generates low-quality STAR stories | Medium | User curation UI; manual edit/delete; quality score on stories |
| Playwright binary size | Increases deployment artifact by ~200MB | Low | Optional dependency; liveness works HTTP-only without it |
| Long evaluation response time | 30s+ API call causes timeout | Medium | Async pattern: return 202 with evaluation ID, poll for completion |

## Test Plan

### Unit Tests

**EvaluationService**:
- Happy path: all 7 blocks extracted, score computed correctly
- Partial blocks: subset requested, only those blocks populated
- AI unavailable: graceful failure, no partial state saved
- Score computation: verify weighted average logic for each archetype
- Mock: AiProvider (returns canned block responses), JobPostingRepository

**CoverLetterGenerationService**:
- Happy path: full 10-step flow produces persisted letter
- Missing evaluation: works without prior evaluation (skips block E context)
- Keyword mirroring: verify extracted keywords appear in output
- Mock: AiProvider, PersonalProfileLoader

**LivenessCheckService**:
- HTTP check returns 200 with active job → ACTIVE
- HTTP check returns 404 → EXPIRED
- HTTP check returns 403/timeout → falls through to Playwright
- Playwright finds expired text → EXPIRED
- Playwright timeout → UNCERTAIN
- Mock: WebClient, PlaywrightRunner

**FollowUpCadenceService**:
- Schedule computation: 3 intervals produce correct dates
- Overdue detection: past-due items marked correctly
- Max attempts: no new follow-up after limit reached
- Mock: FollowUpRepository, Clock (for date testing)

**PatternAnalysisService**:
- Funnel computation: correct conversion rates from test data
- Blocker analysis: identifies most common rejection stage
- Score threshold: recommends minimum score with positive outcomes
- Mock: ApplicationRepository with test data sets

### Integration Tests

**Evaluation E2E** (Testcontainers + WireMock):
- Full evaluation persists all blocks to PostgreSQL
- Re-evaluation replaces existing evaluation
- Concurrent evaluation requests handled safely (409)
- WireMock stubs AiProvider HTTP calls

**Liveness E2E** (Testcontainers):
- HTTP check updates job_posting.liveness_status
- Scheduler processes batch of applied jobs

**Follow-Up Scheduler** (Testcontainers):
- Daily check marks overdue items, doesn't duplicate

### End-to-End Tests

- Evaluate a job → read evaluation → generate cover letter using evaluation context → prepare interview
- Apply to job → follow-up cadence starts → scheduler marks overdue → dashboard shows badge
- Liveness check on applied job → EXPIRED → dashboard shows badge

### Non-Functional Tests

- Evaluation response time: < 45s for full 7-block evaluation
- Liveness HTTP check: < 5s per job (10s timeout)
- Pattern analysis query: < 2s for 1000 applications
- Concurrent evaluation limit: system handles 3 parallel evaluations without degradation
