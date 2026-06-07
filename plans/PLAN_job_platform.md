# Plan: JobHunter — Autonomous Job Discovery & Resume Tailoring Platform

## Overview

Autonomous job discovery platform targeting backend engineering roles in Germany/EU. Self-expanding company registry discovers employers through MCP-powered job board signals, detects their ATS platform, and crawls directly via public APIs. Extracts skills, scores match against personal profile, and feeds everything into an AI application pipeline via MCP server. Learns over time which sources and companies produce interviews.

**Product statement:** "Autonomously discover backend jobs across Europe, rank them for Sam, and feed them into an AI application pipeline."

## Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Backend | Spring Boot 3.x (Java 21) | Core expertise |
| Database | PostgreSQL 16 | Relational, JSONB for raw content |
| Migrations | Liquibase | Schema versioning |
| Scheduling | Spring Scheduler + Quartz | Crawl + discovery orchestration |
| AI Integration | Provider-agnostic (OpenAI + Anthropic APIs). Config selects provider at runtime. | Skill extraction, resume tailoring |
| MCP Server | TypeScript sidecar (ecosystem-native) | Expose tools to AI agents |
| MCP Sidecars | jobspy-mcp, mcp-stepstone | Discovery signals (replaceable) |
| Dashboard | React + Vite (or HTMX for speed) | Job browsing, pipeline tracking |
| Browser Automation | Playwright (Java) | Workday protected instances only |
| Container Runtime | Colima + Docker Compose | Lightweight Docker on macOS (no Docker Desktop) |

## Architecture

```
        [DiscoveryProvider]        [DiscoveryProvider]     [DiscoveryProvider]
        [jobspy-mcp]               [mcp-stepstone]         [LinkedIn Alerts]
        (replaceable)              (replaceable)           (replaceable)
              |                          |                       |
              v                          v                       v
         Company Discovery Engine (YOUR CODE)
         (resolve career endpoints, detect ATS)
              |
              v
         Company Registry (self-expanding)
         + CareerEndpoint table (1:many)
              |
     ---------+---------+----------+
     |        |         |          |
     v        v         v          v
  [Custom   [Custom   [Custom   [Custom
  Greenhouse Lever]   Ashby]   Workday]
  extractor]
     |        |         |          |
     ----------------------------------
                        |
                  Job Database
                        |
           -------------+-------------
           |            |            |
        Skills       Scoring       Dedup
           |            |            |
           -------------+-------------
                        |
                  [Your MCP Server]
                        |
           -------------+-------------
           |                         |
        AI Agents          [Reactive Resume MCP]
                        |
                  Outcome Tracking
                  (learns which sources produce interviews)
```

## Testing Strategy

- Unit: Extractor parsing, normalization, dedup fingerprinting, company discovery logic, ATS detection heuristics, career endpoint resolution
- Integration: End-to-end discovery -> registry -> extract -> store flows against recorded responses (WireMock)
- Done when: Discovery pipeline autonomously adds new companies, extractors pass against fixtures, MCP server responds to all tools, dedup merges same job from 2+ sources

## Phases

### Phase 1: Core Platform + Easy Extractors (Week 1-2)

**Goal:** Spring Boot app that pulls jobs from Greenhouse, Lever, Ashby and stores them.

- Step 1: Spring Boot project scaffolding (PostgreSQL, Liquibase, basic config)
- Step 2: Canonical JobPosting model with salary fields (see Data Model below)
- Step 3: Company + CareerEndpoint tables (1 company = many career URLs)
- Step 4: JobExtractor interface + Greenhouse implementation (boards-api.greenhouse.io/v1/boards/{token}/jobs, no auth)
- Step 5: Lever extractor (handle BOTH global api.lever.co AND EU api.eu.lever.co)
- Step 6: Ashby extractor (api.ashbyhq.com/posting-api/job-board/{name} with includeCompensation=true)
- Step 7: Language filter (applied at ingestion, before any AI processing):
  - SKIP if JD text is in German (detect via language detection library, e.g., lingua)
  - SKIP if English JD has strict German language requirement above B1 (regex + keyword scan: "German C1", "German C2", "Deutsch C1", "fließend Deutsch", "German native", "Muttersprache")
  - KEEP if German mentioned as "nice to have" or "B1 sufficient" or "basic German"
  - Saves AI cost: no skill extraction or scoring on skipped jobs
- Step 8: Scheduled crawl runner (every 4 hours, per-endpoint isolation)
- Step 9: Seed 50 target companies manually (bootstrap only -- discovery engine replaces this)
- Step 10: WireMock-based integration tests with recorded API responses

**Key Design Decisions:**
- Lever EU: Probe both instances on first crawl, store which responds per company
- Error handling: Per-endpoint crawl isolation (one failure doesn't block others)
- Incremental: Track lastCrawledAt, compare job lists to detect new/removed/changed
- Soft delete: Jobs that disappear get is_active = false, never hard deleted
- One company can have multiple CareerEndpoints (e.g., SAP has Greenhouse + Workday + regional sites)

### Phase 2: Company Discovery Engine (Week 3-4)

**Goal:** Self-expanding registry with replaceable discovery providers.

- Step 1: DiscoveryProvider interface:
  ```java
  public interface DiscoveryProvider {
      String name();
      List<DiscoveredCompany> discover(DiscoveryQuery query);
      boolean isHealthy();
      DiscoveryProviderStats getStats();
  }
  ```
- Step 2: JobSpyProvider -- deploy borgius/jobspy-mcp-server as sidecar
  - Call search_jobs daily: "backend engineer", "Java developer", "Spring Boot"
  - Target: LinkedIn, Indeed, Glassdoor, Google Jobs
  - Location: Germany, Netherlands, remote EU
  - If JobSpy breaks: disable provider, others continue
- Step 3: StepStoneProvider -- deploy kdkiss/mcp-stepstone as sidecar
  - Call search_jobs daily: keywords + German postal codes (10115, 80331, 20095, 60311, 50667)
  - Harvest company names AND job data (StepStone jobs go directly to DB)
- Step 4: LinkedInAlertProvider -- Gmail API
  - Parse LinkedIn notification emails -> extract company names + job titles
  - Zero scraping risk, fully ToS compliant
- Step 5: Career endpoint resolver (the HARD part -- most time-consuming subsystem):
  - Given company name -> find ALL career page URLs (not just one)
  - Produce a ResolutionResult with candidateUrls + confidence + strategy
  - Resolution strategies (tried in order):
    1. PATTERN_MATCH: check {company}.greenhouse.io, jobs.lever.co/{company}, etc. (instant, HIGH confidence)
    2. GOOGLE_SEARCH: "{company name}" careers site:{domain} (medium latency)
    3. LINKEDIN_LINK: company LinkedIn page often links to careers (requires parsing)
    4. REDIRECT_FOLLOW: follow redirects from careers.{domain} to actual ATS endpoint
  - Canonicalization logic for ambiguous results:
    - If direct ATS URL found (greenhouse.io, lever.co, etc.) -> prefer it (highest signal)
    - If multiple ATS URLs found -> company has multiple endpoints (all valid, create multiple CareerEndpoints)
    - If only generic careers page found -> run ATS detection on it
    - If nothing found -> confidence: AMBIGUOUS, queue manual review
  - Store EVERY discovered URL as a CareerEndpoint with confidence score
  - Mark endpoints as verified only after successful first crawl
  - Track ResolutionResult for debugging and improving resolution over time
- Step 6: ATS detector: given career endpoint URL -> identify platform type
  - URL pattern matching (HIGH confidence):
    - boards.greenhouse.io/{slug} -> GREENHOUSE
    - jobs.lever.co/{slug} -> LEVER
    - jobs.eu.lever.co/{slug} -> LEVER_EU
    - jobs.ashbyhq.com/{slug} -> ASHBY
    - *.wd{N}.myworkdayjobs.com -> WORKDAY
  - HTML/script inspection (MEDIUM confidence)
  - Unknown -> mark UNKNOWN, queue for manual review
- Step 7: Auto-registration: company + endpoint(s) + ATS -> add to registry -> first crawl
- Step 8: Discovery dedup: normalize aggressively (SAP / SAP SE / SAP Deutschland -> single company)

**Discovery Pipeline:**
```
DiscoveryProviders (JobSpy / StepStone / LinkedIn Alerts)
        |
        v
Extract Company Name
        |
        v
Normalize (strip suffixes, lowercase)
        |
        v
Already in Registry? --YES--> Check if new endpoint
        |NO                         |YES-> Add CareerEndpoint
        v                           |NO--> Skip
Resolve Career Endpoint URLs
        |
        v (may find multiple)
For each URL: Detect ATS
        |
        v
Supported? --NO--> Queue manual review
        |YES
        v
Add Company + Endpoint(s)
        |
        v
First Crawl (validate)
        |
        v
Status: VERIFIED / FAILED
```

**Growth Tracking (honest metrics):**
- discovered: unique company names seen
- resolved: companies where endpoint(s) found
- active: companies with verified, working endpoint(s)

Realistic: Week 4: 200/120/80. Week 8: 400/220/150. Week 12: 600/300/200.

### Phase 3: Workday + Extraction Routing (Week 5-6)

**Goal:** Add Workday support and route extraction based on ATS type.

- Step 1: Workday JSON API extractor (/wday/cxs/{tenant}/{site}/jobs)
- Step 2: Handle quirks: wd1-wd5 shards, 422 on sortBy, POST pagination
- Step 3: Detect protected instances (403) -> flag PROTECTED
- Step 4: Extraction routing:
  - GREENHOUSE / LEVER / LEVER_EU / ASHBY / WORKDAY -> custom (4h schedule)
  - STEPSTONE -> mcp-stepstone sidecar (daily)
  - UNKNOWN -> skip until coverage measured
- Step 5: Crawl health monitoring (0 jobs for 2+ runs -> alert)
- Step 6: Job expiry (soft delete)
- Step 7: ATS migration detection (re-detect on repeated failure)

**Coverage Measurement (critical decision point):**
- After Phase 3: what % of resolved companies have supported ATS?
- >= 75% -> stop building extractors, focus on scoring/tailoring
- < 60% -> evaluate Apify or additional extractors (Phase 7+)

### Phase 4: AI Skill Extraction + Match Scoring (Week 7-8)

**Goal:** Extract tech stack from every job, compute match score, make both queryable via MCP.

Every job gets TWO things on ingestion:
1. **Extracted tech stack** (structured: languages, frameworks, DBs, cloud, tools, methodologies)
2. **Match score** (0-100 against your profile, with matched/missing breakdown)

Both are stored and returned inline with every search result. No extra call needed to see scores.

- Step 1: Skill extraction: AI provider (default: Haiku) -> structured tech stack with categories
- Step 2: Recruiter contact extraction: if JD contains email/name of hiring contact, extract + store with 90-day TTL
- Step 3: Taxonomy normalization ("K8s" = "Kubernetes", "Postgres" = "PostgreSQL")
- Step 4: Personal profile (YAML): skills, proficiency, seniority, location, salary
- Step 5: Match scoring: weighted overlap, gap severity (critical/moderate/minor)
- Step 6: Batch: only NEW jobs (~$0.50/month for 1000 jobs)
- Step 7: Cache skills, never re-extract unchanged JDs
- Step 8: Adaptive crawl: avg score < 40% over 3+ crawls -> reduce frequency
- Step 9: Nightly job: purge recruiter data older than 90 days (GDPR compliance)
- Step 10: Morning job (6am): compute daily digest, recalculate OpportunityScores for new jobs

### Phase 5: MCP Server + Resume Tailoring (Week 9-10)

**Goal:** Expose platform data via MCP for AI application pipeline.

- Step 1: MCP server (TypeScript, stdio + streamable-http)
- Step 2: MCP Tools:
  - search_jobs(query, location, min_score, source) -- returns jobs ranked by OpportunityScore (not just match). Score + breakdown inline
  - get_job(id) -- full detail: description + tech stack + match score + gaps + recruiter contact (if available, within 90-day window)
  - get_tech_stack(job_id) -- dedicated: returns structured tech stack (languages, frameworks, databases, cloud, tools) for resume tailoring agent
  - score_job(id) -- match % + matched skills + missing skills + apply/maybe/skip
  - list_companies() -- registry with priority scores
  - get_profile() -- personal skills + proficiency levels
  - tailor_resume(job_id) -- uses get_tech_stack internally, generates tailored variant
  - generate_cover_letter(job_id)
  - mark_applied(job_id) -- pipeline update
  - record_outcome(application_id, outcome) -- feedback loop
  - get_pipeline(status?)
  - get_daily_digest() -- morning briefing: new jobs, top opportunity, registry changes, rate trends
  - get_radar() -- strategic view: top opportunities, new this week, companies heating up/cooling down
  - get_discovery_stats() -- discovered/resolved/active
  - get_source_quality() -- which sources produce interviews
  - add_company(careers_url) -- fallback
- Step 3: MCP Resources: profile://skills, profile://resume, jobs://top-opportunities, jobs://daily-digest, jobs://radar
- Step 4: Resume tailoring: NEVER invent experience
- Step 5: Integrate Reactive Resume MCP for PDF

### Phase 6: Outcome Tracking + Dashboard (Week 11-12)

**Goal:** Learn which sources produce interviews. Visual interface.

- Step 1: Outcome tracking:
  - APPLIED -> PHONE_SCREEN -> INTERVIEW -> OFFER / REJECTED
  - Per-company interview rate
  - Per-source quality (which DiscoveryProvider finds interview-producing companies)
- Step 2: Intelligent prioritization:
  - Company A: 30 jobs, 0 interviews -> deprioritize
  - Company B: 5 jobs, 2 interviews -> prioritize
- Step 3: Deduplication engine
- Step 4: Dashboard: jobs, pipeline, registry, discovery stats, quality metrics, coverage %

## Data Model

```
Company {
  id: UUID
  name, normalizedName, domain, country: String
  isActive: Boolean
  status: ENUM (DISCOVERED, PENDING_DETECTION, ACTIVE, PROTECTED, UNSUPPORTED, PAUSED)
  discoveredVia: ENUM (MANUAL, STEPSTONE, LINKEDIN_ALERT, JOBSPY)
  discoveredAt: Timestamp
  avgMatchScore: Integer
  interviewRate: Float          -- outcome learning
  totalApplications, totalInterviews: Integer
  priorityScore: Float          -- composite: interview rate + match quality + volume + recency + location
}

CompanyPriorityScore {
  -- Computed, not stored raw. Recalculated daily.
  -- Formula weights:
  --   interviewRate (0.35)      -- historical interview conversion
  --   avgMatchScore (0.25)      -- how well jobs match your profile
  --   hiringVolume (0.15)       -- more open roles = more chances
  --   recency (0.15)            -- recently posted jobs weighted higher
  --   locationFit (0.10)        -- Germany/remote > random country
  --
  -- Used for:
  --   1. Crawl frequency (high priority -> crawl every 2h instead of 4h)
  --   2. Discovery focus (prioritize resolving high-potential companies)
  --   3. Search result ranking (boost jobs from high-priority companies)
  --   4. Alert threshold (notify immediately for high-priority new jobs)
}

CareerEndpoint {
  id: UUID
  companyId: FK -> Company
  url: String
  atsType: ENUM (GREENHOUSE, LEVER, LEVER_EU, ASHBY, WORKDAY, WORKDAY_PROTECTED, STEPSTONE, UNKNOWN)
  atsSlug, atsShardId: String
  extractionMethod: ENUM (CUSTOM, MCP_STEPSTONE, MANUAL)
  confidence: ENUM (HIGH, MEDIUM, LOW)
  verified: Boolean
  isActive: Boolean
  lastCrawlStatus: ENUM (SUCCESS, EMPTY, ERROR, PROTECTED)
  lastCrawledAt: Timestamp
  crawlFrequencyHours: Integer (default 4, adaptive)
  source: String
}

JobPosting {
  id: UUID
  source: ENUM (GREENHOUSE, LEVER, ASHBY, WORKDAY, STEPSTONE, LINKEDIN, MANUAL)
  endpointId: FK -> CareerEndpoint
  externalId, title: String
  companyId: FK -> Company
  location, locationCity, locationCountry: String
  isRemote: ENUM (ONSITE, HYBRID, REMOTE)
  description: Text
  applyUrl: String
  postedDate, discoveredDate: Date
  employmentType: ENUM (FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP)
  salaryMin, salaryMax: BigDecimal
  salaryCurrency: String (ISO 4217)
  salaryPeriod: ENUM (ANNUAL, MONTHLY, HOURLY)
  rawContent: JSONB
  isActive: Boolean
  deactivatedAt: Timestamp
  fingerprint, dataSource: String
  lastCrawledAt: Timestamp
  recruiterName: String (nullable)    -- extracted from JD if present
  recruiterEmail: String (nullable)   -- extracted from JD if present
  recruiterDataExpiresAt: Timestamp   -- 90 days from extraction (GDPR)
}

JobSkill {
  -- This IS the extracted tech stack. Queryable via get_tech_stack(job_id) MCP tool.
  -- Example for a typical backend JD:
  --   {skillName: "Java", category: LANGUAGE, isRequired: true}
  --   {skillName: "Spring Boot", category: FRAMEWORK, isRequired: true}
  --   {skillName: "PostgreSQL", category: DATABASE, isRequired: true}
  --   {skillName: "Kafka", category: TOOL, isRequired: true}
  --   {skillName: "Kubernetes", category: CLOUD, isRequired: false}
  --   {skillName: "Terraform", category: TOOL, isRequired: false}
  id: UUID
  jobId: FK -> JobPosting
  skillName: String             -- normalized (e.g., "Kubernetes" not "K8s")
  category: ENUM (LANGUAGE, FRAMEWORK, DATABASE, CLOUD, TOOL, METHODOLOGY, SOFT_SKILL)
  isRequired: Boolean           -- true = "must have", false = "nice to have"
  rawMention: String            -- original text from JD (for debugging)
}

MatchScore {
  id: UUID
  jobId: FK -> JobPosting
  overallScore: Integer (0-100)
  matchedSkills, missingSkills: JSONB
  recommendation: ENUM (APPLY, MAYBE, SKIP)
  scoredAt: Timestamp
}

OpportunityScore {
  -- Composite ranking. THE primary sort order for everything user-facing.
  -- Match score alone is wrong. A 95% match at a no-name startup with no salary
  -- loses to an 82% match at Personio with €110k + visa sponsorship.
  id: UUID
  jobId: FK -> JobPosting
  score: Integer (0-100)
  breakdown: JSONB               -- per-factor scores for transparency
  computedAt: Timestamp
  -- Factors (all scored 0-100, neutral=50 when no data):
  --   matchScore (0.30)         -- tech stack fit (always available)
  --   interviewHistory (0.20)   -- 50=no history, 80+=interviewed before, 20-=rejected repeatedly
  --   salary (0.20)             -- 50=not stated, scaled vs your target when known
  --   companyQuality (0.15)     -- 50=unknown, adjusted by size/reputation/eng culture
  --   seniority (0.10)          -- inferred from title (Staff/Senior/Lead)
  --   locationFit (0.05)        -- Germany/remote=100, EU=70, other=30
}

Application {
  id: UUID
  jobId: FK -> JobPosting
  status: ENUM (INTERESTED, APPLIED, PHONE_SCREEN, INTERVIEWING, OFFERED, REJECTED, WITHDRAWN)
  appliedDate: Date
  notes: Text
  resumeVariant: String
  updatedAt: Timestamp
}

JobOutcome {
  id: UUID
  applicationId: FK -> Application
  stage: ENUM (APPLIED, PHONE_SCREEN, INTERVIEW_1, INTERVIEW_2, OFFER, REJECTED, WITHDRAWN)
  occurredAt: Date
  notes: Text
}

DiscoveryEvent {
  id: UUID
  companyId: FK -> Company (nullable)
  companyName, provider, sourceJobTitle, sourceUrl: String
  discoveredAt: Timestamp
  outcome: ENUM (REGISTERED, ALREADY_EXISTS, DETECTION_FAILED, UNSUPPORTED_ATS, NEW_ENDPOINT_ADDED)
}

ResolutionResult {
  id: UUID
  companyId: FK -> Company
  strategy: ENUM (GOOGLE_SEARCH, PATTERN_MATCH, LINKEDIN_LINK, REDIRECT_FOLLOW, MANUAL)
  candidateUrls: JSONB          -- all URLs found during resolution
  selectedUrl: String           -- the one chosen as primary (or null if ambiguous)
  confidence: ENUM (HIGH, MEDIUM, LOW, AMBIGUOUS)
  ambiguityReason: String       -- e.g. "multiple ATS detected", "redirect to different domain"
  resolvedAt: Timestamp
  needsManualReview: Boolean    -- true if confidence = AMBIGUOUS
  -- Example for Delivery Hero:
  --   candidateUrls: [
  --     "deliveryhero.com/careers",
  --     "careers.deliveryhero.com",
  --     "jobs.deliveryhero.com",
  --     "deliveryhero.greenhouse.io"
  --   ]
  --   selectedUrl: "deliveryhero.greenhouse.io" (highest confidence: direct ATS URL)
  --   confidence: HIGH
  --   strategy: PATTERN_MATCH
}
```

## Key Design Decisions

### 1. CareerEndpoint: 1 Company = Many URLs
SAP has Greenhouse + Workday + regional sites. Each endpoint crawled independently. Dedup handles overlap.

### 2. DiscoveryProvider: Replaceable Sources
```java
public interface DiscoveryProvider {
    String name();
    List<DiscoveredCompany> discover(DiscoveryQuery query);
    boolean isHealthy();
    DiscoveryProviderStats getStats();
}
```
If JobSpy breaks, others continue. No single point of failure.

### 3. Honest Growth Metrics
Track: discovered / resolved / active separately. 600 discovered -> 200 active is realistic.

### 4. Coverage-First
Measure after Phase 3. If 4 extractors cover 75%+ -> stop. Data-driven decision on Apify.

### 5. Outcome Feedback Loop
Company B (5 jobs, 2 interviews) > Company A (30 jobs, 0 interviews). System learns to prioritize.

### 6. Company Priority Score (Composite Ranking)

Not just match score. Not just interview rate. A composite:

| Factor | Weight | Signal |
|--------|--------|--------|
| Interview rate | 0.35 | Historical conversion (interviews / applications) |
| Avg match score | 0.25 | How well this company's jobs match your profile |
| Hiring volume | 0.15 | More open roles = more chances to get in |
| Recency | 0.15 | Recently posted jobs weighted higher than stale |
| Location fit | 0.10 | Germany/remote > other EU > non-EU |

Effects:
- High priority companies crawled every 2h (not 4h)
- Discovery focuses on resolving high-potential companies first
- Search results boost jobs from high-priority companies
- New jobs from top-priority companies trigger immediate attention

Over time: system becomes MORE selective, not less. Discovery narrows to what works.

### 7. Company Resolution is the Hardest Subsystem

Not ATS extraction. Not MCP. Not resume tailoring. THIS:
- Company name -> correct careers endpoint(s)
- Multiple domains, redirects, acquisitions, regional sites
- Ambiguous results are common (Delivery Hero has 4+ candidate URLs)

ResolutionResult tracks: strategy used, all candidate URLs, confidence, selected URL.
Ambiguous results go to manual review queue. System improves resolution heuristics over time.

### 8. Provider-Agnostic AI (OpenAI + Anthropic)

```java
public interface AiProvider {
    StructuredOutput extract(String prompt, Schema outputSchema);
    String generate(String prompt);  // free-form (cover letters)
}
```

Implementations:
- AnthropicProvider (Messages API, tool_use for structured output)
- OpenAiProvider (Chat Completions API, response_format/tool_calls for structured output)

Config selects at runtime:
```yaml
ai:
  provider: anthropic  # or: openai, openrouter
  extraction-model: claude-haiku-4-5  # cheap, fast, structured output
  tailoring-model: claude-sonnet-4-5  # quality writing
  # OR:
  # provider: openai
  # extraction-model: gpt-4.1-mini
  # tailoring-model: gpt-4.1
```

Why both:
- Anthropic: better at structured extraction, tool_use is cleaner
- OpenAI: cheaper for high volume, response_format: json_schema is strict
- OpenRouter: access to both + open models (Llama, Mistral) as fallback
- No vendor lock-in: if one raises prices or degrades, switch in config

### 9. Language Filter (Pre-AI Gate)

Applied BEFORE skill extraction (saves AI cost on irrelevant jobs):

| Condition | Action | Detection Method |
|-----------|--------|-----------------|
| JD written in German | SKIP | Language detection library (lingua/langdetect) |
| English JD + German C1/C2/native required | SKIP | Regex: "German C[12]", "Deutsch C[12]", "fließend", "Muttersprache", "native German" |
| English JD + German B1/basic/nice-to-have | KEEP | Pattern: "B1", "nice to have", "preferred", "von Vorteil" |
| English JD + no German mentioned | KEEP | Default pass |

Skipped jobs are stored (for audit) but not scored, not shown in search, not sent to MCP.
Skip reason tracked in DB for tuning filter accuracy.

### 10. OpportunityScore > MatchScore (Primary Ranking)

Match score alone is wrong. A 95% match at an unknown startup beats an 82% match at Personio with €110k + proven interview history? No.

OpportunityScore is the ONE number that ranks everything user-facing:

| Factor | Weight | Cold-start (no data) | Why |
|--------|--------|---------------------|-----|
| Match score | 0.30 | Always available | Tech fit |
| Interview history | 0.20 | Neutral (50/100) -- no penalty for new companies | Warm lead signal |
| Salary | 0.20 | Neutral (50/100) -- unknown != bad | Compensation fit |
| Company quality | 0.15 | Neutral (50/100) until manually tagged or inferred | Reputation, stability |
| Seniority fit | 0.10 | Inferred from title keywords | Role level match |
| Location fit | 0.05 | Always available (from JD) | Geography |

Cold-start behavior:
- New company, never applied: interviewHistory = 50 (neutral, not 0)
- Salary not stated in JD: salary = 50 (neutral, not penalized)
- Company quality unknown: companyQuality = 50 (neutral)
- Effect: new companies compete on match + seniority + location only
- As data accumulates, interview history and salary differentiate

search_jobs, Radar, Daily Digest -- all sort by OpportunityScore, never raw match.

### 11. Daily Digest (Push, Not Pull)

System doesn't wait for "What's new?" It already knows that's your first question.

Every morning at 7am:

  Good morning Sam.
  
  12 new jobs discovered. 3 skipped (German JD).
  
  Top Opportunity:
  Senior Backend Engineer - Personio
  Opportunity Score: 94
  Reasons: Strong Kafka fit. Salary €90-110k. High interview rate at Personio.
  
  2 companies entered registry: Scalable Capital, Contentful
  
  Companies heating up: Personio (+5 roles), Celonis (+3 roles)
  Companies cooling down: SAP (no new roles 30 days)
  
  Interview rate: StepStone 18% -> 20%, JobSpy 4% -> 3%

Delivered via: MCP resource (jobs://daily-digest), email, or dashboard notification.

## Deployment

### Local Deployment (Primary)

Everything runs on your machine via Colima (lightweight Docker on macOS). No cloud dependency.

```
colima start && docker compose up
+-- spring-boot-api (port 8080)
|   +-- REST API
|   +-- Crawl scheduler (Quartz, every 4h)
|   +-- Discovery scheduler (daily)
|   +-- Skill extraction + scoring
+-- postgresql (port 5432)
|   +-- All application data (no storage limits)
+-- jobspy-mcp (sidecar)
|   +-- Discovery provider
+-- mcp-stepstone (sidecar)
|   +-- Discovery provider + StepStone extraction
+-- jobhunter-mcp-server (TypeScript, stdio)
|   +-- Exposes tools to Claude Desktop
+-- dashboard (port 3000, optional)
    +-- React frontend
```

### Local Stack

| Component | Technology | Port/Transport |
|-----------|-----------|----------------|
| Spring Boot API | Java 21 + Spring Boot 3.x | localhost:8080 |
| PostgreSQL | Docker (postgres:16) | localhost:5432 |
| MCP Server | TypeScript (Node/Bun) | stdio (Claude Desktop) |
| jobspy-mcp sidecar | Node.js | stdio (called by API via process) |
| mcp-stepstone sidecar | Python | stdio (called by API via process) |
| Dashboard | React + Vite | localhost:3000 |
| AI Provider | Anthropic/OpenAI API | HTTPS (external, ~$0.50/mo) |

### docker-compose.yml (runs via Colima)

```yaml
services:
  api:
    build: ./api
    ports: ["8080:8080"]
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/jobhunter
      - AI_PROVIDER=anthropic
      - AI_API_KEY=${ANTHROPIC_API_KEY}
    depends_on: [db]

  db:
    image: postgres:16
    ports: ["5432:5432"]
    environment:
      - POSTGRES_DB=jobhunter
      - POSTGRES_USER=jobhunter
      - POSTGRES_PASSWORD=jobhunter
    volumes:
      - pgdata:/var/lib/postgresql/data

  dashboard:
    build: ./dashboard
    ports: ["3000:3000"]
    environment:
      - VITE_API_URL=http://localhost:8080

volumes:
  pgdata:
```

### MCP Server Registration (Claude Desktop)

```json
{
  "mcpServers": {
    "jobhunter": {
      "command": "node",
      "args": ["./mcp-server/dist/index.js"],
      "env": {
        "JOBHUNTER_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Scheduling (Built-in, No External Service)

Spring Boot Quartz handles all scheduling internally:
- Crawl all active endpoints: every 4 hours
- Run discovery providers: daily at 6am
- Recalculate OpportunityScores: daily at 7am
- Purge expired recruiter data: nightly
- Generate daily digest: 7:30am

No GitHub Actions, no UptimeRobot, no external cron. Self-contained.

### Running Cost

| Item | Cost |
|------|------|
| Infrastructure | $0 (all local Docker) |
| PostgreSQL | $0 (no storage limit locally) |
| AI (Haiku, ~1000 jobs/mo) | ~$0.50/mo |
| **Total** | **~$0.50/mo** |

### Cloud Deployment (FUTURE — deferred)

When ready to run 24/7 without machine on:
- Render Free (API) + Neon Free (PostgreSQL) + GitHub Actions (cron) = $0
- Details to be designed when local deployment is proven and stable
- All APIs are REST over HTTP — cloud migration is straightforward

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Career resolution harder than expected | High | Medium | Accept partial rates, manual review queue |
| JobSpy breaks | High | Medium | DiscoveryProvider interface, disable + alert |
| Company normalization false merges | Medium | Medium | Conservative merging, review queue |
| Workday protected (30%) | High | Medium | Accept, flag, skip |
| Discovery yields irrelevant companies | Medium | Low | Outcome feedback deprioritizes |
| Coverage lower than expected | Medium | Medium | Measure, then decide |
| Dashboard scope creep | High | Medium | MCP server IS the UI |

## Non-Goals

- Auto-applying to jobs
- LinkedIn automation on main account
- Non-EU markets (initially)
- Mobile / Multi-user / SaaS
- Apify (defer until coverage measured)
- Building extractors for every ATS

## Fallback Features (Emergency Only)

- Manual job URL import
- Manual company addition (add_company MCP tool)
- Browser bookmarklet

## Success Criteria

- 200+ active companies within 12 weeks (from 50 seed)
- Discovery adds 10+ resolved companies/week without intervention
- ATS coverage >= 75% of resolved companies
- 500+ jobs ingested and scored within 2 months
- Outcome tracking shows interview rate improving over time
- DiscoveryProvider failure does not halt the system
- Zero manual intervention for daily discovery + crawl cycle
