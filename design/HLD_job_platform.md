# HLD: JobHunter — Autonomous Job Discovery & Resume Tailoring Platform

## Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Java 21 | Core expertise, virtual threads, pattern matching |
| Framework | Spring Boot 3.x | REST API, scheduling, DI, data access |
| Database | PostgreSQL 16 | Relational + JSONB for raw content/breakdowns |
| Migrations | Liquibase | Schema versioning, repeatable changesets |
| Scheduling | Spring Scheduler + Quartz | Crawl orchestration (4h), discovery (daily), scoring (daily) |
| AI Integration | Anthropic + OpenAI APIs | Skill extraction (Haiku), resume tailoring (Sonnet) |
| MCP Server | TypeScript (Node.js/Bun) | Expose tools to Claude Desktop via stdio |
| MCP Sidecars | jobspy-mcp (Node.js), mcp-stepstone (Python) | Discovery signal providers |
| Dashboard | React + Vite | Job browsing, pipeline tracking, registry stats |
| Browser Automation | Playwright (Java) | Workday protected instances only |
| Container Runtime | Colima + Docker Compose | Lightweight Docker on macOS |
| Language Detection | Lingua (Java) | German JD filtering pre-AI gate |
| HTTP Client | Spring WebClient (reactive) | ATS API calls, career page resolution |
| Testing | JUnit 5 + WireMock + Testcontainers | Unit, integration, recorded API fixtures |

## Components

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| CrawlEngine | Scheduled extraction from career endpoints | CareerEndpoint repo, JobExtractor registry, LanguageFilter |
| DiscoveryEngine | Self-expanding company registry via providers | DiscoveryProvider implementations, EndpointResolver |
| EndpointResolver | Company name → career URL(s) with confidence | Google Search API, HTTP client, ATS detector |
| AtsDetector | URL → ATS platform type identification | HTTP client (HTML inspection) |
| JobExtractorRegistry | Routes extraction to correct extractor by ATS type | All JobExtractor implementations |
| SkillExtractor | AI-powered tech stack extraction from JD text | AiProvider, SkillTaxonomy |
| MatchScorer | Compute match score against personal profile | PersonalProfile, JobSkill data |
| OpportunityScorer | Composite ranking (match + interview + salary + ...) | MatchScore, Company stats, JobPosting metadata |
| LanguageFilter | Pre-AI gate: skip German JDs, strict German reqs | Lingua library |
| RecruiterExtractor | Extract hiring contact from JD text (GDPR 90-day TTL) | AiProvider |
| OutcomeTracker | Application pipeline + feedback into scoring | Application, JobOutcome repos |
| DailyDigestService | Morning briefing computation | OpportunityScore, DiscoveryEvent, Company stats |
| MCP Server (TS) | Expose all tools to Claude Desktop | Spring Boot REST API (HTTP) |
| Dashboard (React) | Visual interface for jobs, pipeline, registry | Spring Boot REST API (HTTP) |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Claude Desktop (AI Agent)                              │
│                                     │                                           │
│                              stdio transport                                    │
└─────────────────────────────────────┼───────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │     jobhunter-mcp-server (TypeScript) │
                    │     Tools: search_jobs, tailor_    │
                    │     resume, get_radar, etc.        │
                    └─────────────────┬─────────────────┘
                                      │ HTTP (localhost:8080)
                                      │
┌─────────────────────────────────────┼───────────────────────────────────────────┐
│                          Spring Boot API (Java 21)                               │
│                                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  ┌───────────────────┐  │
│  │ REST          │  │ CrawlEngine  │  │ Discovery     │  │ AI Processing     │  │
│  │ Controllers   │  │ (Quartz 4h)  │  │ Engine (daily)│  │ (Skill/Score)     │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  └────────┬──────────┘  │
│         │                  │                  │                    │              │
│  ┌──────┴──────────────────┴──────────────────┴────────────────────┴──────────┐  │
│  │                        Service Layer                                        │  │
│  │  JobService │ CompanyService │ DiscoveryService │ ScoringService │ ...      │  │
│  └──────┬──────────────────────────────────────────────────────────────────────┘  │
│         │                                                                        │
│  ┌──────┴──────────────────────────────────────────────────────────────────────┐  │
│  │                        Repository Layer (Spring Data JPA)                    │  │
│  └──────┬──────────────────────────────────────────────────────────────────────┘  │
└─────────┼────────────────────────────────────────────────────────────────────────┘
          │                        │                              │
          │ JDBC                   │ stdio (process)              │ stdio (process)
          │                        │                              │
┌─────────┴──────┐  ┌─────────────┴──────────┐  ┌───────────────┴────────────┐
│ PostgreSQL 16  │  │ jobspy-mcp (Node.js)   │  │ mcp-stepstone (Python)     │
│ Port 5432      │  │ Discovery signals      │  │ Discovery + StepStone data │
│ All app data   │  │ LinkedIn/Indeed/Glass.  │  │ German job boards          │
└────────────────┘  └────────────────────────┘  └────────────────────────────┘

┌────────────────────────────────────────────┐
│ Dashboard (React + Vite, port 3000)        │
│ HTTP → Spring Boot REST API                │
└────────────────────────────────────────────┘

External APIs (HTTPS outbound):
  • Anthropic Messages API (skill extraction, tailoring)
  • OpenAI Chat Completions API (alternative provider)
  • Greenhouse/Lever/Ashby public APIs (job data)
  • Workday JSON API (job data)
  • Google Custom Search API (endpoint resolution)
  • Reactive Resume MCP (PDF generation)
```

**Communication Patterns:**
- MCP Server → Spring Boot: HTTP REST (localhost:8080)
- Spring Boot → MCP Sidecars: stdio via process spawn (JSON-RPC over stdin/stdout)
- Dashboard → Spring Boot: HTTP REST (localhost:8080)
- Spring Boot → PostgreSQL: JDBC connection pool (HikariCP)
- Spring Boot → AI Providers: HTTPS (external APIs)
- Spring Boot → ATS APIs: HTTPS (public endpoints, no auth for Greenhouse/Lever/Ashby)

## Package Structure

```
jobhunter/
├── api/                                    # Spring Boot application
│   └── src/main/java/dev/jobhunter/
│       ├── JobHunterApplication.java
│       ├── config/
│       │   ├── AppConfig.java              # General beans
│       │   ├── QuartzConfig.java           # Scheduler setup
│       │   ├── AiConfig.java               # Provider selection
│       │   └── WebClientConfig.java        # HTTP clients
│       ├── controller/
│       │   ├── JobController.java          # /api/jobs/**
│       │   ├── CompanyController.java      # /api/companies/**
│       │   ├── PipelineController.java     # /api/pipeline/**
│       │   ├── DiscoveryController.java    # /api/discovery/**
│       │   ├── DigestController.java       # /api/digest/**
│       │   └── ProfileController.java      # /api/profile/**
│       ├── service/
│       │   ├── JobService.java
│       │   ├── CompanyService.java
│       │   ├── CrawlService.java
│       │   ├── DiscoveryService.java
│       │   ├── ScoringService.java
│       │   ├── SkillExtractionService.java
│       │   ├── MatchScoringService.java
│       │   ├── OpportunityScoringService.java
│       │   ├── ResumeTailoringService.java
│       │   ├── DailyDigestService.java
│       │   ├── OutcomeService.java
│       │   └── RecruiterDataService.java
│       ├── discovery/
│       │   ├── DiscoveryProvider.java              # Interface
│       │   ├── DiscoveryQuery.java                 # Query params DTO
│       │   ├── DiscoveredCompany.java              # Result DTO
│       │   ├── DiscoveryProviderStats.java         # Health/stats DTO
│       │   ├── JobSpyProvider.java                 # jobspy-mcp integration
│       │   ├── StepStoneProvider.java              # mcp-stepstone integration
│       │   ├── LinkedInAlertProvider.java          # Gmail API parsing
│       │   └── CompanyNormalizer.java              # Name normalization/dedup
│       ├── resolution/
│       │   ├── EndpointResolver.java               # Interface
│       │   ├── ResolutionStrategy.java             # Enum
│       │   ├── ResolutionResult.java               # Result DTO
│       │   ├── PatternMatchResolver.java           # Direct ATS URL matching
│       │   ├── GoogleSearchResolver.java           # Google Custom Search
│       │   ├── RedirectFollowResolver.java         # HTTP redirect tracing
│       │   └── AtsDetector.java                    # URL → ATS type
│       ├── extraction/
│       │   ├── JobExtractor.java                   # Interface
│       │   ├── ExtractionResult.java               # Result DTO
│       │   ├── GreenhouseExtractor.java
│       │   ├── LeverExtractor.java
│       │   ├── AshbyExtractor.java
│       │   ├── WorkdayExtractor.java
│       │   └── StepStoneExtractor.java             # Delegates to MCP sidecar
│       ├── ai/
│       │   ├── AiProvider.java                     # Interface
│       │   ├── AnthropicProvider.java
│       │   ├── OpenAiProvider.java
│       │   └── SkillTaxonomy.java                  # Normalization mappings
│       ├── filter/
│       │   ├── LanguageFilter.java                 # German detection + req check
│       │   └── FilterResult.java                   # KEEP/SKIP + reason
│       ├── scoring/
│       │   ├── MatchScorer.java
│       │   ├── OpportunityScorer.java
│       │   └── CompanyPriorityScorer.java
│       ├── model/
│       │   ├── Company.java                        # JPA entity
│       │   ├── CareerEndpoint.java
│       │   ├── JobPosting.java
│       │   ├── JobSkill.java
│       │   ├── MatchScore.java
│       │   ├── OpportunityScore.java
│       │   ├── Application.java
│       │   ├── JobOutcome.java
│       │   ├── DiscoveryEvent.java
│       │   └── ResolutionResult.java
│       ├── model/enums/
│       │   ├── AtsType.java
│       │   ├── CompanyStatus.java
│       │   ├── DiscoverySource.java
│       │   ├── RemoteType.java
│       │   ├── EmploymentType.java
│       │   ├── SalaryPeriod.java
│       │   ├── SkillCategory.java
│       │   ├── Recommendation.java
│       │   ├── ApplicationStatus.java
│       │   ├── OutcomeStage.java
│       │   ├── CrawlStatus.java
│       │   ├── Confidence.java
│       │   └── DiscoveryOutcome.java
│       ├── repository/
│       │   ├── CompanyRepository.java
│       │   ├── CareerEndpointRepository.java
│       │   ├── JobPostingRepository.java
│       │   ├── JobSkillRepository.java
│       │   ├── MatchScoreRepository.java
│       │   ├── OpportunityScoreRepository.java
│       │   ├── ApplicationRepository.java
│       │   ├── JobOutcomeRepository.java
│       │   ├── DiscoveryEventRepository.java
│       │   └── ResolutionResultRepository.java
│       ├── scheduler/
│       │   ├── CrawlScheduler.java                 # Every 4h (adaptive)
│       │   ├── DiscoveryScheduler.java             # Daily 6am
│       │   ├── ScoringScheduler.java               # Daily 7am
│       │   ├── DigestScheduler.java                # Daily 7:30am
│       │   └── GdprPurgeScheduler.java             # Nightly recruiter data cleanup
│       ├── mcp/
│       │   └── McpSidecarClient.java               # Stdio JSON-RPC client
│       └── dto/
│           ├── JobSearchRequest.java
│           ├── JobSearchResponse.java
│           ├── JobDetailResponse.java
│           ├── TechStackResponse.java
│           ├── TailorResumeRequest.java
│           ├── DailyDigestResponse.java
│           ├── RadarResponse.java
│           └── ... (other request/response DTOs)
│
├── mcp-server/                              # TypeScript MCP server
│   ├── src/
│   │   ├── index.ts                         # Entry point, stdio transport
│   │   ├── tools/                           # Tool definitions
│   │   │   ├── searchJobs.ts
│   │   │   ├── getJob.ts
│   │   │   ├── getTechStack.ts
│   │   │   ├── scoreJob.ts
│   │   │   ├── tailorResume.ts
│   │   │   ├── generateCoverLetter.ts
│   │   │   ├── markApplied.ts
│   │   │   ├── recordOutcome.ts
│   │   │   ├── getPipeline.ts
│   │   │   ├── getDailyDigest.ts
│   │   │   ├── getRadar.ts
│   │   │   ├── listCompanies.ts
│   │   │   ├── getProfile.ts
│   │   │   ├── getDiscoveryStats.ts
│   │   │   ├── getSourceQuality.ts
│   │   │   └── addCompany.ts
│   │   ├── resources/                       # MCP resources
│   │   │   ├── profile.ts
│   │   │   └── jobs.ts
│   │   └── client.ts                        # HTTP client to Spring Boot API
│   ├── package.json
│   └── tsconfig.json
│
├── dashboard/                               # React frontend
│   ├── src/
│   │   ├── App.tsx
│   │   ├── pages/
│   │   │   ├── Jobs.tsx
│   │   │   ├── Pipeline.tsx
│   │   │   ├── Companies.tsx
│   │   │   ├── Discovery.tsx
│   │   │   └── Digest.tsx
│   │   └── components/
│   ├── package.json
│   └── vite.config.ts
│
├── docker-compose.yml
├── Dockerfile.api
├── Dockerfile.dashboard
└── profile.yaml                             # Personal skills/preferences
```

## Interfaces

### DiscoveryProvider

```java
public interface DiscoveryProvider {
    /** Unique provider identifier */
    String name();

    /** Execute discovery query, return new company signals */
    List<DiscoveredCompany> discover(DiscoveryQuery query);

    /** Health check — false disables provider temporarily */
    boolean isHealthy();

    /** Stats for monitoring (calls made, companies found, errors) */
    DiscoveryProviderStats getStats();
}

public record DiscoveryQuery(
    List<String> keywords,        // "backend engineer", "Java developer"
    List<String> locations,       // "Germany", "Netherlands", "remote"
    LocalDate since              // Only results after this date
) {}

public record DiscoveredCompany(
    String companyName,
    String sourceJobTitle,       // The job that revealed this company
    String sourceUrl,            // URL where company was found
    String careerUrlHint        // If provider gives career page URL directly
) {}

public record DiscoveryProviderStats(
    int totalCalls,
    int successfulCalls,
    int companiesDiscovered,
    LocalDateTime lastCallAt,
    Duration avgLatency
) {}
```

### JobExtractor

```java
public interface JobExtractor {
    /** Which ATS types this extractor handles */
    Set<AtsType> supportedTypes();

    /** Extract all active jobs from an endpoint */
    ExtractionResult extract(CareerEndpoint endpoint);

    /** Quick health check against endpoint */
    boolean canExtract(CareerEndpoint endpoint);
}

public record ExtractionResult(
    List<RawJobData> jobs,
    int totalFound,
    ExtractionStatus status,      // SUCCESS, PARTIAL, EMPTY, ERROR, PROTECTED
    String errorMessage,
    Duration elapsed
) {}

public record RawJobData(
    String externalId,
    String title,
    String location,
    String description,           // Full JD text (HTML stripped)
    String applyUrl,
    String rawJson,               // Original API response (stored as JSONB)
    BigDecimal salaryMin,
    BigDecimal salaryMax,
    String salaryCurrency,
    LocalDate postedDate
) {}
```

### AiProvider

```java
public interface AiProvider {
    /** Structured extraction (skills, recruiter info) */
    <T> T extract(String prompt, String content, Class<T> outputType);

    /** Free-form generation (cover letters, tailored summaries) */
    String generate(String systemPrompt, String userPrompt);

    /** Provider health check */
    boolean isAvailable();

    /** Provider name for config/logging */
    String name();
}
```

### EndpointResolver

```java
public interface EndpointResolver {
    /** Resolve company name to career endpoint URL(s) */
    ResolutionResult resolve(String companyName, @Nullable String domain);
}

public record ResolutionResult(
    List<CandidateUrl> candidateUrls,
    @Nullable String selectedUrl,
    Confidence confidence,
    ResolutionStrategy strategyUsed,
    @Nullable String ambiguityReason,
    boolean needsManualReview
) {}

public record CandidateUrl(
    String url,
    AtsType detectedAts,
    Confidence confidence,
    ResolutionStrategy discoveredVia
) {}
```

### LanguageFilter

```java
public interface LanguageFilter {
    /** Check if job should be kept or skipped */
    FilterResult filter(String jobTitle, String jobDescription);
}

public record FilterResult(
    FilterDecision decision,      // KEEP, SKIP
    String reason                 // "German JD", "German C2 required", null if KEEP
) {}

public enum FilterDecision { KEEP, SKIP }
```

### MatchScorer

```java
public interface MatchScorer {
    /** Compute match score for a job against personal profile */
    MatchScoreResult score(JobPosting job, List<JobSkill> skills, PersonalProfile profile);
}

public record MatchScoreResult(
    int overallScore,             // 0-100
    List<String> matchedSkills,
    List<SkillGap> missingSkills,
    Recommendation recommendation // APPLY, MAYBE, SKIP
) {}

public record SkillGap(
    String skillName,
    SkillCategory category,
    GapSeverity severity          // CRITICAL, MODERATE, MINOR
) {}
```

### OpportunityScorer

```java
public interface OpportunityScorer {
    /** Compute composite opportunity score */
    OpportunityScoreResult score(JobPosting job, MatchScore matchScore, Company company);
}

public record OpportunityScoreResult(
    int score,                    // 0-100
    Map<String, Integer> breakdown,  // factor -> score (0-100)
    // Keys: matchScore, interviewHistory, salary, companyQuality, seniority, locationFit
    LocalDateTime computedAt
) {}
```

## REST API Endpoints

### Jobs

| Method | Path | Request | Response | Description |
|--------|------|---------|----------|-------------|
| GET | `/api/jobs` | `?query&location&minScore&source&page&size` | `Page<JobSummaryDto>` | Search jobs (sorted by OpportunityScore) |
| GET | `/api/jobs/{id}` | — | `JobDetailDto` | Full job detail + tech stack + scores |
| GET | `/api/jobs/{id}/tech-stack` | — | `TechStackDto` | Structured tech stack for resume tailoring |
| GET | `/api/jobs/{id}/score` | — | `ScoreBreakdownDto` | Match + opportunity score breakdown |
| GET | `/api/jobs/daily-digest` | — | `DailyDigestDto` | Morning briefing |
| GET | `/api/jobs/radar` | — | `RadarDto` | Strategic view (top opps, heating/cooling) |

### Companies

| Method | Path | Request | Response | Description |
|--------|------|---------|----------|-------------|
| GET | `/api/companies` | `?status&page&size&sort` | `Page<CompanySummaryDto>` | List companies with priority scores |
| GET | `/api/companies/{id}` | — | `CompanyDetailDto` | Company + endpoints + stats |
| POST | `/api/companies` | `{careersUrl}` | `CompanyDto` | Manual company addition |

### Pipeline

| Method | Path | Request | Response | Description |
|--------|------|---------|----------|-------------|
| GET | `/api/pipeline` | `?status` | `List<ApplicationDto>` | Application pipeline |
| POST | `/api/pipeline/{jobId}/apply` | `{resumeVariant?, notes?}` | `ApplicationDto` | Mark job as applied |
| PUT | `/api/pipeline/{applicationId}/outcome` | `{stage, notes?}` | `JobOutcomeDto` | Record outcome |

### Discovery

| Method | Path | Request | Response | Description |
|--------|------|---------|----------|-------------|
| GET | `/api/discovery/stats` | — | `DiscoveryStatsDto` | discovered/resolved/active counts |
| GET | `/api/discovery/source-quality` | — | `List<SourceQualityDto>` | Per-source interview rates |
| GET | `/api/discovery/events` | `?page&size` | `Page<DiscoveryEventDto>` | Recent discovery events |

### Profile

| Method | Path | Request | Response | Description |
|--------|------|---------|----------|-------------|
| GET | `/api/profile` | — | `ProfileDto` | Personal skills + preferences |
| PUT | `/api/profile` | `ProfileDto` | `ProfileDto` | Update profile |

### Resume Tailoring

| Method | Path | Request | Response | Description |
|--------|------|---------|----------|-------------|
| POST | `/api/tailor/{jobId}` | `{emphasis?, excludeSkills?}` | `TailoredResumeDto` | Generate tailored resume content |
| POST | `/api/cover-letter/{jobId}` | `{tone?, focus?}` | `CoverLetterDto` | Generate cover letter |

### Response DTOs

```java
public record JobSummaryDto(
    UUID id, String title, String companyName, String location,
    RemoteType remoteType, int opportunityScore, int matchScore,
    Recommendation recommendation, List<String> topSkills,
    BigDecimal salaryMin, BigDecimal salaryMax, String salaryCurrency,
    LocalDate postedDate, String source
) {}

public record JobDetailDto(
    UUID id, String title, String companyName, UUID companyId,
    String location, RemoteType remoteType, String description,
    String applyUrl, int opportunityScore, int matchScore,
    Map<String, Integer> scoreBreakdown,
    List<String> matchedSkills, List<SkillGapDto> missingSkills,
    Recommendation recommendation,
    TechStackDto techStack,
    @Nullable String recruiterName, @Nullable String recruiterEmail,
    BigDecimal salaryMin, BigDecimal salaryMax, String salaryCurrency,
    LocalDate postedDate, String source
) {}

public record TechStackDto(
    List<SkillDto> languages,
    List<SkillDto> frameworks,
    List<SkillDto> databases,
    List<SkillDto> cloud,
    List<SkillDto> tools,
    List<SkillDto> methodologies
) {}

public record SkillDto(String name, boolean required, @Nullable String rawMention) {}

public record DailyDigestDto(
    int newJobsCount, int skippedCount, String skipReason,
    JobSummaryDto topOpportunity,
    List<String> newCompanies,
    List<CompanyTrendDto> heatingUp,
    List<CompanyTrendDto> coolingDown,
    Map<String, Double> sourceInterviewRates
) {}

public record RadarDto(
    List<JobSummaryDto> topOpportunities,    // Top 10 by OpportunityScore
    List<JobSummaryDto> newThisWeek,         // New high-score jobs
    List<CompanyTrendDto> heatingUp,
    List<CompanyTrendDto> coolingDown,
    DiscoveryStatsDto discoveryStats
) {}
```

## MCP Tools Specification

### search_jobs

```typescript
{
  name: "search_jobs",
  description: "Search jobs ranked by OpportunityScore with inline score breakdown",
  inputSchema: {
    type: "object",
    properties: {
      query: { type: "string", description: "Search query (title, skills, company)" },
      location: { type: "string", description: "Location filter" },
      min_score: { type: "number", description: "Minimum OpportunityScore (0-100)" },
      source: { type: "string", enum: ["GREENHOUSE","LEVER","ASHBY","WORKDAY","STEPSTONE"] },
      limit: { type: "number", default: 20 }
    }
  },
  // Returns: Array of { id, title, company, location, opportunityScore, matchScore, recommendation, topSkills, salary }
}
```

### get_job

```typescript
{
  name: "get_job",
  description: "Full job detail including description, tech stack, scores, recruiter contact",
  inputSchema: {
    type: "object",
    properties: {
      id: { type: "string", description: "Job UUID" }
    },
    required: ["id"]
  }
}
```

### get_tech_stack

```typescript
{
  name: "get_tech_stack",
  description: "Structured tech stack for resume tailoring (languages, frameworks, DBs, cloud, tools)",
  inputSchema: {
    type: "object",
    properties: {
      job_id: { type: "string", description: "Job UUID" }
    },
    required: ["job_id"]
  }
  // Returns: { languages: [{name, required}], frameworks: [...], databases: [...], cloud: [...], tools: [...], methodologies: [...] }
}
```

### tailor_resume

```typescript
{
  name: "tailor_resume",
  description: "Generate tailored resume content for a specific job. NEVER invents experience.",
  inputSchema: {
    type: "object",
    properties: {
      job_id: { type: "string", description: "Job UUID" },
      emphasis: { type: "array", items: { type: "string" }, description: "Skills to emphasize" },
      format: { type: "string", enum: ["json", "pdf"], default: "json" }
    },
    required: ["job_id"]
  }
  // Returns: { summary, experience (reordered/rephrased), skills (highlighted), pdfUrl? }
}
```

### generate_cover_letter

```typescript
{
  name: "generate_cover_letter",
  description: "Generate personalized cover letter matching job requirements to experience",
  inputSchema: {
    type: "object",
    properties: {
      job_id: { type: "string", description: "Job UUID" },
      tone: { type: "string", enum: ["professional", "enthusiastic", "concise"], default: "professional" },
      focus: { type: "string", description: "Specific aspect to emphasize" }
    },
    required: ["job_id"]
  }
}
```

### mark_applied

```typescript
{
  name: "mark_applied",
  description: "Mark a job as applied, moves to pipeline",
  inputSchema: {
    type: "object",
    properties: {
      job_id: { type: "string" },
      resume_variant: { type: "string", description: "Which resume version used" },
      notes: { type: "string" }
    },
    required: ["job_id"]
  }
}
```

### record_outcome

```typescript
{
  name: "record_outcome",
  description: "Record application outcome (feeds back into scoring)",
  inputSchema: {
    type: "object",
    properties: {
      application_id: { type: "string" },
      outcome: { type: "string", enum: ["PHONE_SCREEN","INTERVIEW_1","INTERVIEW_2","OFFER","REJECTED","WITHDRAWN"] },
      notes: { type: "string" }
    },
    required: ["application_id", "outcome"]
  }
}
```

### get_daily_digest

```typescript
{
  name: "get_daily_digest",
  description: "Morning briefing: new jobs, top opportunity, registry changes, rate trends",
  inputSchema: { type: "object", properties: {} }
}
```

### get_radar

```typescript
{
  name: "get_radar",
  description: "Strategic view: top opportunities, new this week, companies heating up/cooling down",
  inputSchema: { type: "object", properties: {} }
}
```

### get_pipeline

```typescript
{
  name: "get_pipeline",
  description: "Application pipeline status",
  inputSchema: {
    type: "object",
    properties: {
      status: { type: "string", enum: ["INTERESTED","APPLIED","PHONE_SCREEN","INTERVIEWING","OFFERED","REJECTED","WITHDRAWN"] }
    }
  }
}
```

### score_job

```typescript
{
  name: "score_job",
  description: "Match percentage + matched/missing skills + apply/maybe/skip recommendation",
  inputSchema: {
    type: "object",
    properties: { id: { type: "string" } },
    required: ["id"]
  }
}
```

### list_companies

```typescript
{
  name: "list_companies",
  description: "Registry with priority scores, interview rates, ATS types",
  inputSchema: {
    type: "object",
    properties: {
      status: { type: "string", enum: ["ACTIVE","DISCOVERED","PAUSED"] },
      sort: { type: "string", enum: ["priority","name","interviewRate"], default: "priority" }
    }
  }
}
```

### get_profile

```typescript
{
  name: "get_profile",
  description: "Personal skills, proficiency levels, preferences",
  inputSchema: { type: "object", properties: {} }
}
```

### get_discovery_stats

```typescript
{
  name: "get_discovery_stats",
  description: "Discovery pipeline: discovered/resolved/active counts",
  inputSchema: { type: "object", properties: {} }
}
```

### get_source_quality

```typescript
{
  name: "get_source_quality",
  description: "Which discovery sources produce interviews",
  inputSchema: { type: "object", properties: {} }
}
```

### add_company

```typescript
{
  name: "add_company",
  description: "Manually add company via careers URL (fallback)",
  inputSchema: {
    type: "object",
    properties: {
      careers_url: { type: "string", description: "Careers page or ATS board URL" },
      company_name: { type: "string", description: "Optional company name override" }
    },
    required: ["careers_url"]
  }
}
```

### MCP Resources

| URI | Description |
|-----|-------------|
| `profile://skills` | Personal skill list with proficiency |
| `profile://resume` | Current base resume content |
| `jobs://top-opportunities` | Top 10 by OpportunityScore |
| `jobs://daily-digest` | Latest daily digest |
| `jobs://radar` | Current radar view |

## Data Flow

### Discovery Pipeline

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | DiscoveryScheduler | Trigger daily at 6am | DiscoveryService |
| 2 | DiscoveryService | Iterate all healthy DiscoveryProviders | Each provider |
| 3 | JobSpyProvider / StepStoneProvider / LinkedInAlertProvider | Execute queries, return DiscoveredCompany list | DiscoveryService |
| 4 | CompanyNormalizer | Normalize name (strip suffixes, lowercase, dedup) | Check registry |
| 5 | CompanyRepository | Check if company exists | Branch |
| 6a | (exists) CompanyService | Check if new endpoint discovered | Add endpoint or skip |
| 6b | (new) EndpointResolver | Resolve career URL(s) with confidence | AtsDetector |
| 7 | AtsDetector | Detect ATS type from URL patterns + HTML inspection | CompanyService |
| 8 | CompanyService | Register Company + CareerEndpoint(s) | CrawlService |
| 9 | CrawlService | Execute first crawl to verify endpoint | Mark VERIFIED/FAILED |
| 10 | DiscoveryEvent | Log outcome for audit | Done |

**Error Flow:** Provider failure → mark unhealthy, log, continue with other providers. Resolution failure → confidence=AMBIGUOUS, needsManualReview=true. First crawl failure → endpoint status=FAILED, retry next cycle.

### Crawl Cycle

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | CrawlScheduler | Select endpoints due for crawl (by frequency + priority) | CrawlService |
| 2 | CrawlService | For each endpoint (isolated): route to extractor | JobExtractorRegistry |
| 3 | JobExtractorRegistry | Select extractor by AtsType | Specific extractor |
| 4 | GreenhouseExtractor / LeverExtractor / etc. | Call ATS API, parse response | CrawlService |
| 5 | CrawlService | Compare with existing jobs (by externalId) | Detect new/removed |
| 6 | LanguageFilter | Filter new jobs (skip German, strict German req) | Store or skip |
| 7 | JobPostingRepository | Upsert new jobs, soft-delete disappeared | Trigger scoring |
| 8 | SkillExtractionService | AI extract tech stack for new jobs | JobSkillRepository |
| 9 | RecruiterExtractor | Extract recruiter contact if present | JobPostingRepository |
| 10 | MatchScoringService | Compute match score for new jobs | MatchScoreRepository |
| 11 | OpportunityScoringService | Compute opportunity score | OpportunityScoreRepository |
| 12 | CrawlService | Update endpoint lastCrawledAt, lastCrawlStatus | Done |

**Error Flow:** Extractor error → log, mark endpoint ERROR, continue other endpoints. AI provider error → retry once, skip scoring if still failing (score on next cycle). 0 jobs for 2+ runs → alert, trigger ATS migration detection.

### Skill Extraction Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | SkillExtractionService | Receive new job with description text | Check cache |
| 2 | Cache check | JD fingerprint unchanged? → skip (return cached) | AiProvider |
| 3 | AiProvider | Structured extraction prompt + JD → skills JSON | Parse result |
| 4 | SkillTaxonomy | Normalize ("K8s" → "Kubernetes", "Postgres" → "PostgreSQL") | Store |
| 5 | JobSkillRepository | Persist skills with category + isRequired + rawMention | Done |

### Resume Tailoring Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | MCP Tool: tailor_resume | Receive job_id | REST API call |
| 2 | ResumeTailoringService | Load job tech stack + personal profile | AiProvider |
| 3 | AiProvider | Generate tailored content (summary, experience ordering) | Validate |
| 4 | Validation | Verify no invented experience (cross-check with profile) | Format |
| 5 | (if format=pdf) | Call Reactive Resume MCP → generate PDF | Return URL |
| 6 | Return | Tailored resume JSON/PDF URL | MCP response |

## Data Model (Database Schema)

### Tables

```sql
-- Company registry (self-expanding)
CREATE TABLE company (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    domain          VARCHAR(255),
    country         VARCHAR(100),
    is_active       BOOLEAN DEFAULT true,
    status          VARCHAR(50) NOT NULL DEFAULT 'DISCOVERED',
    discovered_via  VARCHAR(50) NOT NULL,
    discovered_at   TIMESTAMP NOT NULL DEFAULT now(),
    avg_match_score INTEGER,
    interview_rate  FLOAT DEFAULT 0,
    total_applications INTEGER DEFAULT 0,
    total_interviews   INTEGER DEFAULT 0,
    priority_score  FLOAT DEFAULT 50,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_company_normalized_name ON company(normalized_name);
CREATE INDEX idx_company_status ON company(status);
CREATE INDEX idx_company_priority ON company(priority_score DESC);

-- Career endpoints (1 company = many)
CREATE TABLE career_endpoint (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id           UUID NOT NULL REFERENCES company(id),
    url                  VARCHAR(1024) NOT NULL,
    ats_type             VARCHAR(50) NOT NULL,
    ats_slug             VARCHAR(255),
    ats_shard_id         VARCHAR(10),
    extraction_method    VARCHAR(50) NOT NULL DEFAULT 'CUSTOM',
    confidence           VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    verified             BOOLEAN DEFAULT false,
    is_active            BOOLEAN DEFAULT true,
    last_crawl_status    VARCHAR(50),
    last_crawled_at      TIMESTAMP,
    crawl_frequency_hours INTEGER DEFAULT 4,
    source               VARCHAR(255),
    created_at           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_endpoint_company ON career_endpoint(company_id);
CREATE INDEX idx_endpoint_active ON career_endpoint(is_active, ats_type);
CREATE INDEX idx_endpoint_next_crawl ON career_endpoint(is_active, last_crawled_at);

-- Job postings (canonical model)
CREATE TABLE job_posting (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source            VARCHAR(50) NOT NULL,
    endpoint_id       UUID REFERENCES career_endpoint(id),
    external_id       VARCHAR(255) NOT NULL,
    title             VARCHAR(500) NOT NULL,
    company_id        UUID NOT NULL REFERENCES company(id),
    location          VARCHAR(500),
    location_city     VARCHAR(255),
    location_country  VARCHAR(100),
    is_remote         VARCHAR(20) DEFAULT 'ONSITE',
    description       TEXT,
    apply_url         VARCHAR(2048),
    posted_date       DATE,
    discovered_date   DATE NOT NULL DEFAULT CURRENT_DATE,
    employment_type   VARCHAR(50) DEFAULT 'FULL_TIME',
    salary_min        NUMERIC(12,2),
    salary_max        NUMERIC(12,2),
    salary_currency   VARCHAR(3),
    salary_period     VARCHAR(20),
    raw_content       JSONB,
    is_active         BOOLEAN DEFAULT true,
    deactivated_at    TIMESTAMP,
    fingerprint       VARCHAR(64),
    language_filter   VARCHAR(20) DEFAULT 'KEEP',
    filter_reason     VARCHAR(255),
    recruiter_name    VARCHAR(255),
    recruiter_email   VARCHAR(255),
    recruiter_data_expires_at TIMESTAMP,
    last_crawled_at   TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_job_source_external ON job_posting(source, external_id);
CREATE INDEX idx_job_company ON job_posting(company_id);
CREATE INDEX idx_job_active ON job_posting(is_active, language_filter);
CREATE INDEX idx_job_discovered ON job_posting(discovered_date DESC);
CREATE INDEX idx_job_fingerprint ON job_posting(fingerprint);
CREATE INDEX idx_job_recruiter_expiry ON job_posting(recruiter_data_expires_at)
    WHERE recruiter_data_expires_at IS NOT NULL;

-- Extracted skills (tech stack per job)
CREATE TABLE job_skill (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    skill_name  VARCHAR(100) NOT NULL,
    category    VARCHAR(50) NOT NULL,
    is_required BOOLEAN DEFAULT true,
    raw_mention VARCHAR(255)
);

CREATE INDEX idx_skill_job ON job_skill(job_id);
CREATE INDEX idx_skill_name ON job_skill(skill_name);

-- Match scores (tech fit against profile)
CREATE TABLE match_score (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    overall_score   INTEGER NOT NULL,
    matched_skills  JSONB NOT NULL,
    missing_skills  JSONB NOT NULL,
    recommendation  VARCHAR(20) NOT NULL,
    scored_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_match_job ON match_score(job_id);

-- Opportunity scores (composite ranking — THE primary sort)
CREATE TABLE opportunity_score (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    score       INTEGER NOT NULL,
    breakdown   JSONB NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_opp_job ON opportunity_score(job_id);
CREATE INDEX idx_opp_score ON opportunity_score(score DESC);

-- Application pipeline
CREATE TABLE application (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES job_posting(id),
    status          VARCHAR(50) NOT NULL DEFAULT 'INTERESTED',
    applied_date    DATE,
    notes           TEXT,
    resume_variant  VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_application_job ON application(job_id);
CREATE INDEX idx_application_status ON application(status);

-- Outcome tracking (feedback loop)
CREATE TABLE job_outcome (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id  UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    stage           VARCHAR(50) NOT NULL,
    occurred_at     DATE NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_outcome_application ON job_outcome(application_id);

-- Discovery audit log
CREATE TABLE discovery_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID REFERENCES company(id),
    company_name    VARCHAR(255) NOT NULL,
    provider        VARCHAR(100) NOT NULL,
    source_job_title VARCHAR(500),
    source_url      VARCHAR(2048),
    discovered_at   TIMESTAMP NOT NULL DEFAULT now(),
    outcome         VARCHAR(50) NOT NULL
);

CREATE INDEX idx_discovery_provider ON discovery_event(provider, discovered_at DESC);

-- Resolution tracking (debugging/improving resolution)
CREATE TABLE resolution_result (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id          UUID NOT NULL REFERENCES company(id),
    strategy            VARCHAR(50) NOT NULL,
    candidate_urls      JSONB NOT NULL,
    selected_url        VARCHAR(2048),
    confidence          VARCHAR(20) NOT NULL,
    ambiguity_reason    VARCHAR(500),
    resolved_at         TIMESTAMP NOT NULL DEFAULT now(),
    needs_manual_review BOOLEAN DEFAULT false
);

CREATE INDEX idx_resolution_company ON resolution_result(company_id);
CREATE INDEX idx_resolution_review ON resolution_result(needs_manual_review)
    WHERE needs_manual_review = true;
```

### Entity Relationships

```
Company (1) ──────< CareerEndpoint (many)
Company (1) ──────< JobPosting (many)
CareerEndpoint (1) ──────< JobPosting (many)
JobPosting (1) ──────< JobSkill (many)
JobPosting (1) ────── MatchScore (1)
JobPosting (1) ────── OpportunityScore (1)
JobPosting (1) ────── Application (0..1)
Application (1) ──────< JobOutcome (many)
Company (1) ──────< DiscoveryEvent (many)
Company (1) ──────< ResolutionResult (many)
```

## Docker Service Topology

```yaml
# docker-compose.yml — full local stack
services:
  api:
    build:
      context: ./api
      dockerfile: Dockerfile
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/jobhunter
      SPRING_DATASOURCE_USERNAME: jobhunter
      SPRING_DATASOURCE_PASSWORD: jobhunter
      AI_PROVIDER: anthropic
      AI_API_KEY: ${ANTHROPIC_API_KEY}
      AI_EXTRACTION_MODEL: claude-haiku-4-5
      AI_TAILORING_MODEL: claude-sonnet-4-5
      GOOGLE_SEARCH_API_KEY: ${GOOGLE_SEARCH_API_KEY}
      GOOGLE_SEARCH_CX: ${GOOGLE_SEARCH_CX}
    depends_on:
      db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  db:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: jobhunter
      POSTGRES_USER: jobhunter
      POSTGRES_PASSWORD: jobhunter
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U jobhunter"]
      interval: 10s
      timeout: 5s
      retries: 5

  dashboard:
    build:
      context: ./dashboard
      dockerfile: Dockerfile
    ports: ["3000:3000"]
    environment:
      VITE_API_URL: http://localhost:8080
    depends_on:
      - api

volumes:
  pgdata:
```

**Service Dependency Graph:**

```
dashboard ──depends──> api ──depends──> db
                        │
                        ├──spawns──> jobspy-mcp (stdio process)
                        └──spawns──> mcp-stepstone (stdio process)

jobhunter-mcp-server (separate, stdio to Claude Desktop)
        │
        └──HTTP──> api (localhost:8080)
```

**Note:** MCP sidecars (jobspy-mcp, mcp-stepstone) are NOT Docker services. They are spawned as child processes by the Spring Boot API via stdio JSON-RPC. This avoids Docker networking complexity for MCP protocol (which uses stdin/stdout).

The MCP server for Claude Desktop runs outside Docker (registered in Claude Desktop config, launched as Node.js process).

## Sequence Diagrams

### Discovery Pipeline (Daily)

```
DiscoveryScheduler           DiscoveryService          JobSpyProvider         StepStoneProvider       CompanyNormalizer        EndpointResolver         AtsDetector          CompanyService
      │                            │                         │                       │                       │                       │                      │                     │
      │──trigger(6am)──────────────>│                         │                       │                       │                       │                      │                     │
      │                            │──discover(query)────────>│                       │                       │                       │                      │                     │
      │                            │<─────[DiscoveredCompany]─│                       │                       │                       │                      │                     │
      │                            │──discover(query)─────────────────────────────────>│                       │                       │                      │                     │
      │                            │<─────[DiscoveredCompany]─────────────────────────│                       │                       │                      │                     │
      │                            │                         │                       │                       │                       │                      │                     │
      │                            │──normalize(names)───────────────────────────────────────────────────────>│                       │                      │                     │
      │                            │<─────[normalized]───────────────────────────────────────────────────────│                       │                      │                     │
      │                            │                         │                       │                       │                       │                      │                     │
      │                            │ [for each new company]  │                       │                       │                       │                      │                     │
      │                            │──resolve(companyName)────────────────────────────────────────────────────────────────────────────>│                      │                     │
      │                            │                         │                       │                       │                       │──detectAts(url)──────>│                     │
      │                            │                         │                       │                       │                       │<─────[AtsType]────────│                     │
      │                            │<─────[ResolutionResult]─────────────────────────────────────────────────────────────────────────│                      │                     │
      │                            │                         │                       │                       │                       │                      │                     │
      │                            │──register(company, endpoints)────────────────────────────────────────────────────────────────────────────────────────────────────────────────>│
      │                            │<─────[Company]──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────│
      │                            │──firstCrawl(endpoint)───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────>│
      │                            │<─────[VERIFIED/FAILED]──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────│
```

### Crawl Cycle (Every 4h)

```
CrawlScheduler       CrawlService        JobExtractorRegistry    GreenhouseExtractor     LanguageFilter      SkillExtractionService    ScoringService
      │                    │                       │                       │                    │                       │                      │
      │──trigger───────────>│                       │                       │                    │                       │                      │
      │                    │──selectDueEndpoints()──│                       │                    │                       │                      │
      │                    │                       │                       │                    │                       │                      │
      │                    │ [for each endpoint, isolated]                  │                    │                       │                      │
      │                    │──getExtractor(atsType)─>│                       │                    │                       │                      │
      │                    │<─────[extractor]────────│                       │                    │                       │                      │
      │                    │──extract(endpoint)──────────────────────────────>│                    │                       │                      │
      │                    │<─────[ExtractionResult]─────────────────────────│                    │                       │                      │
      │                    │                       │                       │                    │                       │                      │
      │                    │ [for each new job]    │                       │                    │                       │                      │
      │                    │──filter(title, desc)───────────────────────────────────────────────>│                       │                      │
      │                    │<─────[KEEP/SKIP]────────────────────────────────────────────────────│                       │                      │
      │                    │                       │                       │                    │                       │                      │
      │                    │ [if KEEP]             │                       │                    │                       │                      │
      │                    │──saveJob()             │                       │                    │                       │                      │
      │                    │──extractSkills(job)────────────────────────────────────────────────────────────────────────>│                      │
      │                    │<─────[JobSkill[]]──────────────────────────────────────────────────────────────────────────│                      │
      │                    │──scoreJob(job, skills)─────────────────────────────────────────────────────────────────────────────────────────────>│
      │                    │<─────[MatchScore + OpportunityScore]───────────────────────────────────────────────────────────────────────────────│
```

### Resume Tailoring via MCP

```
Claude Desktop      MCP Server (TS)        Spring Boot API         AiProvider          Reactive Resume MCP
      │                    │                       │                    │                       │
      │──tailor_resume─────>│                       │                    │                       │
      │  (job_id)          │──GET /api/jobs/{id}/   │                    │                       │
      │                    │  tech-stack────────────>│                    │                       │
      │                    │<─────[TechStackDto]────│                    │                       │
      │                    │──GET /api/profile──────>│                    │                       │
      │                    │<─────[ProfileDto]──────│                    │                       │
      │                    │──POST /api/tailor/{id}─>│                    │                       │
      │                    │                       │──generate(prompt)──>│                       │
      │                    │                       │<─────[tailored]─────│                       │
      │                    │                       │──validate(no invention)                     │
      │                    │<─────[TailoredResume]──│                    │                       │
      │                    │                       │                    │                       │
      │                    │ [if format=pdf]        │                    │                       │
      │                    │──apply_resume_patch────────────────────────────────────────────────>│
      │                    │<─────[pdf URL]─────────────────────────────────────────────────────│
      │<─────[result]──────│                       │                    │                       │
```

## Configuration Management

### Application Configuration (application.yaml)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/jobhunter
    username: ${DB_USER:jobhunter}
    password: ${DB_PASSWORD:jobhunter}
  jpa:
    hibernate:
      ddl-auto: validate  # Liquibase handles schema
  liquibase:
    change-log: classpath:db/changelog/master.xml

ai:
  provider: ${AI_PROVIDER:anthropic}
  api-key: ${AI_API_KEY}
  extraction-model: ${AI_EXTRACTION_MODEL:claude-haiku-4-5}
  tailoring-model: ${AI_TAILORING_MODEL:claude-sonnet-4-5}

crawl:
  default-frequency-hours: 4
  high-priority-frequency-hours: 2
  batch-size: 50                    # Max endpoints per crawl run
  timeout-seconds: 30               # Per-endpoint timeout

discovery:
  schedule: "0 0 6 * * *"          # Daily 6am
  providers:
    jobspy:
      enabled: true
      keywords: ["backend engineer", "Java developer", "Spring Boot", "Kotlin"]
      locations: ["Germany", "Netherlands", "remote"]
    stepstone:
      enabled: true
      postal-codes: ["10115", "80331", "20095", "60311", "50667"]
    linkedin-alerts:
      enabled: false                # Requires Gmail API setup

scoring:
  schedule: "0 0 7 * * *"         # Daily 7am
  opportunity-weights:
    match-score: 0.30
    interview-history: 0.20
    salary: 0.20
    company-quality: 0.15
    seniority: 0.10
    location-fit: 0.05
  company-priority-weights:
    interview-rate: 0.35
    avg-match-score: 0.25
    hiring-volume: 0.15
    recency: 0.15
    location-fit: 0.10

digest:
  schedule: "0 30 7 * * *"        # Daily 7:30am

gdpr:
  recruiter-ttl-days: 90
  purge-schedule: "0 0 2 * * *"   # Nightly 2am

resolution:
  google-search:
    api-key: ${GOOGLE_SEARCH_API_KEY:}
    cx: ${GOOGLE_SEARCH_CX:}
  strategies-order: [PATTERN_MATCH, GOOGLE_SEARCH, REDIRECT_FOLLOW]
  timeout-seconds: 15
```

### Personal Profile (profile.yaml)

```yaml
name: "Sam"
target-role: "Senior/Staff Backend Engineer"
seniority: SENIOR
location:
  preferred: ["Germany", "Netherlands"]
  remote-preference: HYBRID  # REMOTE, HYBRID, ONSITE
salary:
  target: 100000
  currency: EUR
  period: ANNUAL
skills:
  - name: Java
    proficiency: EXPERT
    years: 8
  - name: Spring Boot
    proficiency: EXPERT
    years: 6
  - name: Kotlin
    proficiency: ADVANCED
    years: 3
  - name: PostgreSQL
    proficiency: ADVANCED
    years: 6
  - name: Kafka
    proficiency: INTERMEDIATE
    years: 3
  - name: Kubernetes
    proficiency: INTERMEDIATE
    years: 2
  - name: AWS
    proficiency: INTERMEDIATE
    years: 4
  - name: Docker
    proficiency: ADVANCED
    years: 5
  - name: TypeScript
    proficiency: INTERMEDIATE
    years: 2
languages:
  - language: English
    level: C2
  - language: German
    level: A2
```

## Error Handling Strategy

| Layer | Strategy | Implementation |
|-------|----------|----------------|
| Crawl per-endpoint | Isolation — one endpoint failure doesn't block others | try/catch per endpoint in loop, log + mark ERROR status |
| Discovery provider | Circuit breaker — unhealthy providers skipped | `isHealthy()` check, 3 consecutive failures → disable 1h |
| AI provider | Retry once + fallback | RetryTemplate (1 retry, 2s backoff). If both fail, skip scoring this cycle |
| HTTP calls (ATS APIs) | Timeout + retry | 30s timeout, 1 retry with exponential backoff. 429 → respect Retry-After |
| MCP sidecar | Process crash recovery | Restart process on next call. Log last stderr. |
| Database | Transaction per-job | Each job upsert is one transaction. Partial failures don't corrupt batch |
| Resolution | Graceful degradation | If strategy fails, try next. All fail → AMBIGUOUS + manual review |
| Language filter | Permissive default | If detection uncertain → KEEP (false negatives preferred over false positives) |

**Global Error Patterns:**
- All exceptions logged with correlation ID (endpoint/company/job context)
- Failed operations produce structured error events (queryable for debugging)
- No silent failures — every error path produces an observable signal
- Crawl health: 0 jobs for 2+ consecutive runs → alert (possible ATS migration)

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| MCP sidecars as child processes | stdio spawn (not Docker svc) | MCP protocol is stdio-native, avoids Docker networking complexity | Docker services with HTTP bridge | Simpler, but process management needed (restart on crash) |
| Single Spring Boot monolith | One deployable for all backend logic | Simplicity for single-user local system, no inter-service latency | Microservices | No horizontal scaling, but irrelevant for local single-user |
| OpportunityScore as primary ranking | Composite score over raw match | Match alone misses salary, interview history, company quality signals | Separate sort options | More complex computation, but better decision quality |
| Language filter before AI | Skip German JDs pre-extraction | Saves ~30% AI cost (no skill extraction on irrelevant jobs) | Post-extraction filter | Tiny risk of false-positive skips on edge cases |
| JSONB for raw content + breakdowns | PostgreSQL JSONB columns | Flexible schema for varied ATS responses, queryable | Separate normalized tables | Less strict schema, but eliminates migration churn |
| Liquibase over Flyway | Liquibase XML/YAML changesets | Better rollback support, precondition checks | Flyway SQL migrations | Slightly more verbose, but safer for schema evolution |
| TypeScript MCP server (not Java) | Ecosystem-native MCP SDK | MCP TypeScript SDK is most mature, community-standard | Java MCP SDK | Extra service, but leverages best MCP tooling |
| Soft delete for jobs | is_active=false, never hard delete | Audit trail, dedup detection, job reappearance handling | Hard delete | Storage grows, but negligible for local PostgreSQL |
| Personal profile as YAML file | Static file in repo, loaded at startup | Simple to edit, version-controlled, no UI needed for single user | Database-stored profile | Requires restart to update, but changes are rare |
| Quartz for scheduling | Spring Quartz integration | Persistent job store, misfire handling, cron expressions | Spring @Scheduled | @Scheduled lacks persistence; missed jobs (laptop sleep) lost |
| Workday as Phase 3 | Defer complexity | Workday has quirks (shards, POST pagination, protected instances) | Build all extractors in Phase 1 | Later availability, but reduces initial complexity |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Career endpoint resolution accuracy < 60% | Many companies unresolvable → limited registry growth | High | Accept partial rates, manual review queue, improve heuristics iteratively |
| JobSpy MCP breaks (upstream dependency) | One discovery provider offline | High | DiscoveryProvider interface — disable + alert, others continue |
| Company normalization false merges | Wrong companies merged (different "SAP" entities) | Medium | Conservative merging: exact normalized_name match only, manual review for fuzzy |
| Workday protected instances (~30%) | Cannot extract jobs from some large employers | High | Flag as PROTECTED, skip gracefully. Accept coverage gap. |
| AI provider rate limits | Skill extraction delayed for large batches | Medium | Batch processing with backoff, priority queue (high-score companies first) |
| ATS API changes without notice | Extractor breaks for specific ATS | Medium | WireMock fixtures detect drift. Per-endpoint isolation limits blast radius |
| Laptop sleep interrupts scheduled jobs | Missed crawl/discovery cycles | Medium | Quartz persistent job store + misfire policy (fire immediately on wake) |
| Google Search API quota exhaustion | Resolution blocked for new companies | Low | Cache results, rate-limit resolution to 50/day, fallback to PATTERN_MATCH only |
| GDPR recruiter data TTL drift | Stale personal data retained past 90 days | Low | Nightly purge job + monitoring alert if purge fails |

## Test Plan

### Unit Tests

**Target:** 80%+ line coverage on service layer and domain logic.

| Component | Key Scenarios | Mocks |
|-----------|--------------|-------|
| GreenhouseExtractor | Parse multi-job response, handle empty board, handle malformed JSON | WebClient (return recorded JSON) |
| LeverExtractor | Parse EU vs global response, handle pagination, detect empty | WebClient |
| AshbyExtractor | Parse with/without compensation, handle missing fields | WebClient |
| WorkdayExtractor | Multi-page POST, handle 422 on sortBy, detect protected (403) | WebClient |
| LanguageFilter | German text → SKIP, English + German C2 → SKIP, English + B1 ok → KEEP, ambiguous → KEEP | None (pure logic) |
| CompanyNormalizer | "SAP SE" → "sap", "SAP Deutschland GmbH" → "sap", case insensitivity | None (pure logic) |
| AtsDetector | URL patterns for all ATS types, unknown URL → UNKNOWN | WebClient (for HTML inspection) |
| MatchScorer | Full overlap → 100, partial → proportional, critical gaps reduce more | PersonalProfile (fixed test profile) |
| OpportunityScorer | Cold-start (neutral=50), all-data, salary-missing, interview-boost | MatchScore, Company (mocked data) |
| SkillTaxonomy | "K8s" → "Kubernetes", "Postgres" → "PostgreSQL", unknown passthrough | None (mapping table) |
| DailyDigestService | Normal day, no new jobs, heating/cooling detection | Repositories (mocked) |

### Integration Tests

**Strategy:** Testcontainers (PostgreSQL) + WireMock (ATS APIs).

| Test | Components | Verification |
|------|-----------|--------------|
| Discovery → Registry → Crawl | DiscoveryService + CompanyService + CrawlService | New company registered, endpoint created, first crawl produces jobs |
| Crawl → Filter → Score | CrawlService + LanguageFilter + ScoringService | German JD skipped, English JD scored, scores persisted |
| Outcome → Priority update | OutcomeService + CompanyPriorityScorer | Recording interview increases company priority_score |
| Full extraction pipeline | Extractor → Filter → Skills → Match → Opportunity | Job with full scoring chain from raw API response |
| Dedup detection | CrawlService with same job from 2 endpoints | Single job_posting with both sources tracked |
| GDPR purge | RecruiterDataService + scheduler | Recruiter data older than 90 days removed |

### End-to-End Tests

| Journey | Steps | Success Criteria |
|---------|-------|-----------------|
| New company discovery | Provider discovers company → resolved → registered → first crawl → jobs scored | Jobs appear in search_jobs with OpportunityScore |
| MCP tool: search_jobs | Call MCP tool with min_score=70 | Returns jobs sorted by OpportunityScore, all ≥70 |
| MCP tool: tailor_resume | Call with job_id | Returns tailored content matching job tech stack, no invented experience |
| Application pipeline | mark_applied → record_outcome(INTERVIEW) | Company interview_rate increases, priority_score adjusts |
| Daily digest generation | Trigger digest scheduler | Digest contains correct counts, top opportunity, trends |

### Non-Functional Tests

| Requirement | Target | Verification |
|-------------|--------|--------------|
| Crawl isolation | One endpoint failure doesn't affect others | Kill one WireMock stub, verify other endpoints still crawled |
| Crawl performance | 50 endpoints in < 5 minutes | Timed integration test |
| AI extraction latency | < 3s per job (Haiku) | Timed test against real API (manual/CI-excluded) |
| Database query performance | Search with filters < 200ms | Query plan analysis, index verification |
| MCP response time | < 500ms for search_jobs | Timed integration test (API → DB → response) |
| Scheduler misfire recovery | Jobs fire after laptop wake | Quartz misfire policy test |
