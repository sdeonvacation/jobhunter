# Plan: Unified Aggregator Contract

## Overview

Unify all job sources (ATS, Direct career pages, Aggregators) under a single `FetchStrategy` interface at the project root level. Extract duplicated ingestion logic into shared services. Aggregators are NOT ATS types — introduce `JobSource` enum for origin tracking. Strategy pattern makes adding new sources trivial (one class + yaml config).

## Tech Stack

- Java 21, Spring Boot 3.3.5
- Existing filter chain (LanguageFilter, RoleRelevanceFilter, LocationFilter, YoeFilter, DeduplicationFilter)
- Existing PipelineScheduler (Quartz, parallel execution)
- Existing repositories (JobPostingRepository, CompanyRepository)

## Testing Strategy

- Unit: Each strategy tested with mocked transport (WireMock for HTTP, mock MCP client, fake CLI output)
- Unit: `AggregatorIngestionService` tested with mocked source + mocked filters/repos
- Integration: End-to-end test with Testcontainers verifying company resolution + dedup across sources
- Done when: All existing LinkedIn/Indeed tests pass unchanged, new tests cover shared service + BSJ/Arbeitnow strategies

## Architecture

### Three source types, one interface:

```
FetchStrategy.java (universal interface)
├── ats/         → company known from endpoint, structured API
├── direct/      → company known from endpoint, unstructured HTML + AI
└── aggregator/  → company UNKNOWN, must resolve per job
```

### Package structure:

```
dev.jobhunter/
├── strategy/                           # ROOT LEVEL — universal fetch abstraction
│   ├── FetchStrategy.java             # Interface: ALL sources implement this
│   ├── FetchContext.java              # Input: endpoint OR search params
│   ├── FetchResult.java               # Output: jobs + metadata
│   ├── ats/                           # ATS platform strategies
│   │   ├── GreenhouseStrategy.java
│   │   ├── LeverStrategy.java
│   │   ├── WorkdayStrategy.java
│   │   └── ... (one per ATS)
│   ├── direct/                        # Direct career page strategies
│   │   └── AiPageStrategy.java
│   └── aggregator/                    # Multi-employer aggregator strategies
│       ├── McpStrategy.java           # LinkedIn
│       ├── CliStrategy.java           # Indeed (jobspy-js)
│       ├── AiAggregatorStrategy.java  # BSJ (HTML + AI, extracts company name)
│       └── RestApiStrategy.java       # Arbeitnow
├── ingestion/                          # Orchestration
│   ├── CrawlService.java             # Company-scoped (ATS + Direct)
│   ├── AggregatorIngestionService.java # Search-scoped (Aggregators)
│   ├── StrategyRegistry.java          # Routes source → strategy
│   └── IngestionStats.java
├── source/                             # Aggregator source configs
│   ├── SourceConfig.java              # Interface: name(), jobSource(), frequencyHours()
│   ├── LinkedInSource.java
│   ├── IndeedSource.java
│   ├── BsjSource.java
│   └── ArbeitnowSource.java
├── filter/                             # Shared filter chain
├── model/enums/
│   ├── AtsType.java                   # ATS platforms only
│   └── JobSource.java                 # Universal origin (ATS + Aggregator + Direct)
└── (controller, repository, scheduler, service, scoring — unchanged)
```

### Key design rule:

- `FetchResult.jobs[].companyName == null` → company from endpoint (ATS/Direct)
- `FetchResult.jobs[].companyName != null` → must resolve company (Aggregator)

## Phases

### Phase 1: Source Model + Strategy Interface

- Step 1: Create `JobSource` enum to replace `AtsType` as origin tracker on `JobPosting`:
  - ATS sources: `GREENHOUSE`, `LEVER`, `ASHBY`, etc. (mapped from AtsType)
  - Aggregator sources: `LINKEDIN`, `INDEED`, `BERLIN_STARTUP_JOBS`, `ARBEITNOW`
  - Direct: `DIRECT`
- Step 2: Migrate `JobPosting.source` from `AtsType` to `JobSource` (Liquibase migration)
- Step 3: Update `JobPostingRepository` queries that filter by source:
  - Affects: source tab filtering in `JobController` (ats/linkedin/indeed tabs)
  - Affects: `findBySource`, `existsBySourceAndExternalId`, exclusion queries
- Step 4: Create `FetchStrategy` interface in `dev.jobhunter.strategy`:
  - `FetchResult fetch(FetchContext context)`
  - Strategy handles pagination internally (respects `maxPages`)
- Step 5: Create `FetchContext` record:
  - `endpoint` — CareerEndpoint (for ATS/Direct, null for aggregators)
  - `keywords` — search terms (for aggregators)
  - `locations` — location filters (for aggregators)
  - `maxResults` — cap per fetch
  - `maxPages` — pagination limit
  - `config` — source-specific config map
- Step 6: Create `FetchResult` record:
  - `jobs` — List of job records (externalId, title, companyName, location, description, applyUrl, postedDate, salary)
  - `companyName` on each job: null for ATS/Direct, populated for Aggregators
- Step 7: Create `SourceConfig` interface (for aggregators):
  - `String name()`
  - `JobSource sourceType()`
  - `DiscoverySource discoverySource()`
  - `FetchStrategy strategy()`
  - `FetchContext buildContext()`
  - `int frequencyHours()` — read from `discovery.providers.{name}.frequency-hours` in application.yaml
- Step 8: Create `AggregatorIngestionService`:
  - `ingest(SourceConfig source)` → fetch → dedup → enrich-or-create → filter → company resolve → persist
  - Returns `IngestionStats` (fetched, enriched, created, filtered, errors)
- Step 9: Create `StrategyRegistry` — routes `AtsType` or source name to correct strategy

### Phase 2: Migrate ATS Extractors to Strategy Pattern

- Step 1: Rename existing `JobExtractor` implementations to `*Strategy` (e.g., `GreenhouseExtractor` → `GreenhouseStrategy`)
- Step 2: Move to `strategy/ats/` package
- Step 3: Adapt interface from `JobExtractor.extract(CareerEndpoint)` → `FetchStrategy.fetch(FetchContext)`
- Step 4: Move `GenericAiExtractor` → `strategy/direct/AiPageStrategy`
- Step 5: Update `CrawlService` to use `StrategyRegistry` instead of `JobExtractorRegistry`
- Step 6: Verify all existing ATS crawl tests pass

### Phase 3: Adapt LinkedIn + Indeed to Strategy Pattern

- Step 1: Create `strategy/aggregator/McpStrategy.java` wrapping existing MCP client + parse logic
- Step 2: Create `strategy/aggregator/CliStrategy.java` wrapping jobspy-js subprocess + JSON parse
- Step 3: Create `source/LinkedInSource.java` implementing `SourceConfig` (config from profile.linkedInSearch)
- Step 4: Create `source/IndeedSource.java` implementing `SourceConfig` (config from profile.indeedSearch)
- Step 5: Refactor `LinkedInJobSearchService` to delegate to `AggregatorIngestionService.ingest(linkedInSource)`
- Step 6: Refactor `IndeedJobSearchService` to delegate to `AggregatorIngestionService.ingest(indeedSource)`
- Step 7: Verify all existing tests pass

### Phase 4: YAML-Driven Aggregator Sources (No Hardcoded Source Classes)

Instead of one Java class per aggregator (BsjSource.java, ArbeitnowSource.java), sources are declared entirely in YAML. A generic `DynamicSourceConfig` reads the list and instantiates sources at startup:

- Step 1: Define aggregator sources in `application.yaml`:
  ```yaml
  aggregator:
    sources:
      - name: berlinstartupjobs
        strategy: ai           # maps to AiAggregatorStrategy
        job-source: BERLIN_STARTUP_JOBS
        discovery-source: BERLIN_STARTUP_JOBS
        url: https://berlinstartupjobs.com/engineering/
        frequency-hours: 12
        max-results: 30
      - name: arbeitnow
        strategy: rest-api     # maps to RestApiStrategy
        job-source: ARBEITNOW
        discovery-source: ARBEITNOW
        url: https://www.arbeitnow.com/api/job-board-api
        frequency-hours: 6
        max-results: 50
  ```
- Step 2: Create `DynamicSourceConfigLoader` — reads `aggregator.sources[]` from config, resolves strategy by name from `StrategyRegistry`, creates `SourceConfig` instances
- Step 3: LinkedIn and Indeed remain as dedicated `@Component` source classes (they have complex wiring: MCP client, rate limiter, CLI subprocess). But new aggregators require zero Java code — just YAML.
- Step 4: `JobSource` and `DiscoverySource` enum values for new sources still need to be added to Java enums (unavoidable with `@Enumerated`)

### Phase 5: Generalize Filters (Remove Country/Role/Language Hardcoding)

- Step 1: Rename profile.yaml keys:
  - `filters.location.germany-cities` → `filters.location.target-cities`
  - Keep backward compatibility: if old key exists, map to new key
- Step 2: Add `filters.language.target` to profile.yaml (e.g., `target: en`):
  - LanguageFilter rejects jobs that REQUIRE a language other than target
  - Remove hardcoded German patterns ("deutsch C1", "fließend deutsch", "muttersprache")
  - Make patterns configurable: `filters.language.exclude-patterns` (list of regexes for "requires $LANGUAGE" in any language)
- Step 3: Remove hardcoded locations from `GenericAiExtractor.looksLikeLocation()`:
  - Read city list from profile instead of hardcoded "berlin", "munich", "hamburg"
- Step 4: Verify AI extraction prompts are locale-neutral:
  - No assumptions about country in system prompts
  - Job title/location extraction works regardless of language
- Step 5: Update README/setup guide with example profile.yaml for different regions:
  - Example: US user targeting remote Python roles
  - Example: EU user targeting Berlin-based Java roles

### Phase 6: Update PipelineScheduler + Admin API

- Step 1: Replace direct LinkedIn/Indeed service calls with iteration over `List<SourceConfig>` beans
- Step 2: Each source runs in parallel (existing CompletableFuture pattern)
- Step 3: Conditional on each source's config toggle (`@ConditionalOnProperty`)
- Step 4: Per-source scheduling: respect `source.frequencyHours()` — skip sources not yet due
  - Config: `discovery.providers.{name}.frequency-hours` in application.yaml
  - Track `lastRunAt` per source (DB or in-memory)
- Step 5: Admin endpoint `POST /api/admin/aggregate/{sourceName}` for manual trigger
- Step 6: Admin endpoint `GET /api/admin/aggregators` — list sources + last run stats

## Extensibility

| Action | Steps required |
|--------|---------------|
| New ATS (e.g., iCIMS) | 1 strategy class in `strategy/ats/`, 1 enum value |
| New company (known ATS) | POST to API (zero code) |
| New aggregator (existing strategy type) | YAML entry only (zero code) |
| New aggregator (new transport) | 1 strategy class + YAML entry |
| New direct career page | POST to API with atsType=CUSTOM (zero code) |
| New user in different country | Edit profile.yaml: target-cities, target-language, skills, role patterns |

## User Onboarding

A new user configures the entire system via two files:

1. **`profile.yaml`** — personal config:
   ```yaml
   name: "Jane"
   skills: [{name: "Python", proficiency: "expert"}, ...]
   preferences:
     locations: ["San Francisco", "Remote"]
     seniority: "senior"
   filters:
     role:
       include-patterns: ["engineer", "developer", "SRE"]
       exclude-keywords: ["manager", "director", "sales"]
     location:
       target-cities: ["san francisco", "new york", "seattle"]
       remote-patterns: ["remote", "remote.*us"]
     language:
       target: "en"
       exclude-patterns: ["native spanish required", "español C2"]
     yoe:
       max-years: 8
   ```

2. **`application.yaml`** — aggregator sources:
   ```yaml
   aggregator:
     sources:
       - name: hn-whoishiring
         strategy: ai
         url: https://news.ycombinator.com/item?id=...
         frequency-hours: 24
       - name: weworkremotely
         strategy: rest-api
         url: https://weworkremotely.com/api/...
         frequency-hours: 6
   ```

No code changes needed. Just config.

## Risks

- **JobSource migration**: Changing `JobPosting.source` from `AtsType` to `JobSource` touches DB schema + all queries. Mitigation: Liquibase migration maps existing values 1:1.
- **ATS extractor rename (Phase 2)**: Renaming 13+ extractors + moving packages. Mitigation: purely mechanical refactor, no logic change. One commit per extractor.
- **LinkedIn rate limiting in strategy**: `McpStrategy` must integrate existing `LinkedInRateLimiter`. Mitigation: strategy constructor-injects the limiter.
- **BSJ company name extraction**: AI prompt must reliably extract employer name from HTML. Mitigation: fallback to "Unknown" company, surface in admin health report.
- **Regression**: Refactoring 2 large services (LinkedIn 372 lines, Indeed 338 lines). Mitigation: existing tests must pass at each phase boundary.
- **Language filter generalization**: Removing hardcoded German patterns may cause regressions for existing German-detection behavior. Mitigation: existing patterns become the DEFAULT values in profile.yaml; behavior unchanged unless user modifies config.
- **Dynamic enum values**: YAML-defined sources still need enum values in Java (`JobSource`, `DiscoverySource`). Truly dynamic sources would need String-based storage. Mitigation: accept enum limitation for now; most users won't add more than a handful of sources.
