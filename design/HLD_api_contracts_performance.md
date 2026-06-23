# HLD: API Module Contracts & Performance

## Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Java 21 | Virtual threads for parallelism, record types |
| Framework | Spring Boot 3.3.5 | DI, transaction management, WebClient |
| Database | PostgreSQL 16 | Partial indexes, LEFT JOIN anti-join optimization |
| ORM | Hibernate 6.5.3 | JPA repositories, @Modifying bulk queries |
| Scheduler | Quartz 2.3.2 | Cron-triggered crawl/scoring/pipeline jobs |
| Migrations | Liquibase | Index DDL, reversible changesets |
| Connection Pool | HikariCP | Bounded pool for virtual thread safety |

## Components

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| `JobFilterChain` | Unified filter pipeline (lang→role→loc→visa→yoe) | All 5 filter beans |
| `RawJobInput` | Decoupled filter input DTO | None (record) |
| `FilterChainResult` | Composite result with per-step decisions | FilterResult, VisaFilterResult |
| `AbstractAtsStrategy` | Shared ATS strategy boilerplate (elapsed, truncate, stripHtml, safeExecute) | WebClient, ObjectMapper |
| `ScoringService` | Scoring logic extracted from scheduler | MatchScoringService, OpportunityScoringService, JobPostingRepository |
| `CrawlService` (refactored) | Endpoint crawl orchestration with parallel execution | JobFilterChain, ScoringService, StrategyRegistry |
| `AggregatorIngestionServiceImpl` (refactored) | Source ingestion with batch dedup | JobFilterChain, JobPostingRepository |
| `PipelineScheduler` (refactored) | Virtual-thread orchestration with semaphore | CrawlService, AggregatorIngestionService, ScoringService |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Quartz Schedulers (thin)                       │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────────────┐   │
│  │CrawlScheduler│  │PipelineScheduler │  │ScoringScheduler      │   │
│  └──────┬───────┘  └────────┬─────────┘  └──────────┬───────────┘   │
└─────────┼───────────────────┼───────────────────────┼───────────────┘
          │                   │                       │
          ▼                   ▼                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          Service Layer                                │
│  ┌────────────────┐  ┌─────────────────────────┐  ┌──────────────┐  │
│  │  CrawlService  │  │AggregatorIngestionService│  │ScoringService│  │
│  │  (parallel VT) │  │   (batch dedup)          │  │  (extracted) │  │
│  └───────┬────────┘  └────────────┬─────────────┘  └──────────────┘  │
│          │                        │                                   │
│          ▼                        ▼                                   │
│  ┌────────────────────────────────────────────────────┐              │
│  │              JobFilterChain                          │              │
│  │  ┌────┐  ┌────┐  ┌─────┐  ┌────┐  ┌───┐  ┌─────┐ │              │
│  │  │Lang│→ │Role│→ │ Loc │→ │Visa│→ │YOE│→ │Dedup│ │              │
│  │  └────┘  └────┘  └─────┘  └────┘  └───┘  └─────┘ │              │
│  └────────────────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Strategy Layer                                  │
│  ┌──────────────────────────────────────────────────────────┐        │
│  │              AbstractAtsStrategy                          │        │
│  │  elapsed() | truncate() | stripHtml() | safeExecute()    │        │
│  └──────┬──────────┬──────────┬──────────┬──────────────────┘        │
│         │          │          │          │                            │
│  ┌──────┴──┐ ┌─────┴───┐ ┌───┴────┐ ┌───┴──────┐  (14 strategies)  │
│  │Greenhouse│ │  Lever  │ │Workday │ │SmartRecr.│  ...               │
│  └──────────┘ └─────────┘ └────────┘ └──────────┘                    │
└─────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Repository Layer (optimized)                       │
│  ┌────────────────────────────────────────────────────┐              │
│  │            JobPostingRepository                     │              │
│  │  LEFT JOIN scoring queries | bulk deactivate        │              │
│  │  batch externalId lookup | batch fingerprint check  │              │
│  └────────────────────────────────────────────────────┘              │
│  ┌────────────────────────────────────────────────────┐              │
│  │       PostgreSQL 16 (new indexes)                   │              │
│  │  idx_job_endpoint_active                            │              │
│  │  idx_job_visa_pending (partial)                     │              │
│  │  idx_job_source_active                              │              │
│  └────────────────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────┘
```

**Patterns**: Strategy pattern (FetchStrategy), Template Method (AbstractAtsStrategy), Chain of Responsibility (JobFilterChain), Facade (ScoringService wraps Match + Opportunity scorers).

**Boundaries**: Schedulers are thin Quartz jobs with no business logic. Services own all domain logic. Filter chain is a standalone stateless service. Strategies only parse/fetch — never filter.

## Interfaces

### JobFilterChain

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `apply` | `RawJobInput input, boolean isAggregator, boolean visaExempt` | `FilterChainResult` | Runs lang→role→loc→visa→yoe in sequence; short-circuits on first SKIP. Visa skipped when `visaExempt=true` or location is Germany. | Never throws — catches filter exceptions, logs, returns KEEP (fail-open) |

### RawJobInput (record)

| Field | Type | Purpose |
|-------|------|---------|
| `title` | `String` | Job title for lang + role filters |
| `description` | `String` (nullable) | Description for lang + visa + yoe filters |
| `location` | `String` (nullable) | Location for loc + visa country detection |
| `companyName` | `String` (nullable) | Company for dedup fingerprint (optional, not used in chain) |

### FilterChainResult (record)

| Field | Type | Purpose |
|-------|------|---------|
| `decision` | `FilterDecision` | KEEP or SKIP |
| `reason` | `String` (nullable) | Human-readable skip reason |
| `visaSponsorship` | `VisaSponsorship` (nullable) | Detected visa status (null for DE) |
| `extractedYoe` | `Integer` (nullable) | YOE parsed from description |

### AbstractAtsStrategy

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `fetch` (abstract) | `FetchContext context` | `FetchResult` | Subclass implements ATS-specific logic | Caught by `safeExecute` wrapper |
| `safeExecute` (final) | `Supplier<FetchResult> action, Instant start` | `FetchResult` | Wraps fetch in try-catch, returns FetchResult.error on exception | Logs + returns error result |
| `elapsed` (protected) | `Instant start` | `Duration` | `Duration.between(start, Instant.now())` | — |
| `truncate` (protected) | `String value, int maxLength` | `String` | Null-safe truncation | — |
| `stripHtml` (protected) | `String html` | `String` | Remove HTML tags + decode entities | — |
| `parseIsoDate` (protected) | `String dateStr` | `LocalDate` (nullable) | Parse ISO/ZonedDateTime, returns null on failure | — |
| `textOrNull` (protected) | `JsonNode node, String path` | `String` (nullable) | `node.path(path).asText(null)` with blank→null | — |
| `mapArray` (protected) | `JsonNode arrayNode, Function<JsonNode, T> mapper` | `List<T>` | Iterate + map, skip nulls | — |

### ScoringService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `scoreAllUnscored` | — | `void` | Batch-paginated scoring of all unscored KEEP jobs | Logs + continues on per-batch failure |
| `scoreJobsForEndpoint` | `UUID endpointId` | `void` | Score unscored KEEP jobs for one endpoint | Logs + swallows |
| `scoreJobsForSource` | `JobSource source` | `void` | Score unscored KEEP jobs for one source | Logs + swallows |

### Filter Interfaces (extracted from concrete classes)

Three filters currently lack interface contracts. Extract them for testability and substitutability:

| Interface | Method Signature | Current Impl → Renamed To |
|-----------|-----------------|---------------------------|
| `YoeFilter` | `FilterResult filter(Integer yoe)` + `Integer extractYoe(String description)` | `YoeFilter` → `YoeFilterImpl` |
| `VisaSponsorshipFilter` | `VisaFilterResult filter(String location, String description, boolean isAggregator)` | `VisaSponsorshipFilter` → `VisaSponsorshipFilterImpl` |
| `DeduplicationFilter` | `String generateFingerprint(String title, String company, String location)` | `DeduplicationFilter` → `DeduplicationFilterImpl` |

Already interface-backed (no change needed):

| Interface | Impl |
|-----------|------|
| `LanguageFilter` | `LanguageFilterImpl` |
| `LocationFilter` | `LocationFilterImpl` |
| `RoleRelevanceFilter` | `RoleRelevanceFilterImpl` |

All 6 filter interfaces are injected into `JobFilterChain`. Tests can mock any filter independently.

### FetchStrategy (modified)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `fetch` | `FetchContext context` | `FetchResult` | Fetch jobs from external ATS/source | Wrapped by safeExecute in base class |
| `supportedTypes` | — | `Set<AtsType>` | Returns all ATS types this strategy handles (replaces `supports(AtsType)`) | — |
| `name` | — | `String` | Strategy identifier for logging/registry | — |

### JobPostingRepository (new queries)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `bulkDeactivateByEndpoint` | `UUID endpointId, Set<String> excludeExternalIds, LocalDateTime deactivatedAt` | `int` (rows affected) | `@Modifying` UPDATE ... SET is_active=false WHERE endpoint_id=? AND external_id NOT IN (?) | — |
| `findExternalIdsBySourceAsSet` | `JobSource source` | `Set<String>` | Pre-load all external IDs for batch dedup | — |
| `findFingerprintsBySource` | `JobSource source` | `Set<String>` | Pre-load fingerprints for batch ATS matching | — |

## Data Flow

### Crawl Pipeline (Parallel)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineScheduler | Triggers crawl cycle | CrawlService |
| 2 | CrawlService.crawlAllDueEndpoints | Loads all active non-CUSTOM endpoints | Virtual thread executor |
| 3 | Semaphore (20 permits) | Acquires permit per endpoint | Strategy fetch |
| 4 | FetchStrategy.fetch | HTTP call to ATS API (outside @Transactional) | processFetchResult |
| 5 | CrawlService.persistFetchResult | @Transactional: filter + upsert + deactivate | JobFilterChain |
| 6 | JobFilterChain.apply | Runs filter cascade, returns FilterChainResult | Job persistence |
| 7 | JobPostingRepository.bulkDeactivateByEndpoint | Single UPDATE for missing jobs | ScoringService |
| 8 | ScoringService.scoreJobsForEndpoint | Score new KEEP jobs immediately | Release semaphore |

### Aggregator Ingestion (Batch Dedup)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineScheduler | Triggers source ingestion | AggregatorIngestionServiceImpl |
| 2 | AggregatorIngestionServiceImpl | Fetch all jobs from source | Batch dedup lookup |
| 3 | Repository.findExternalIdsBySourceAsSet | Pre-load known external IDs | Loop filter |
| 4 | Repository.findFingerprintsBySource | Pre-load known fingerprints | Loop filter |
| 5 | Loop: skip known externalId | Skip without DB query | Next job |
| 6 | Loop: check fingerprint match | In-memory Set check | Enrich or create |
| 7 | JobFilterChain.apply | Filter cascade (isAggregator=true, visaExempt from source config) | Persist |
| 8 | ScoringService.scoreJobsForSource | Score after all jobs processed | Complete |

### Backfill Cycle

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | CrawlService (post-crawl) | Invoke DescriptionBackfillers | Backfiller |
| 2 | DescriptionBackfiller.backfill | Fetch descriptions for KEEP jobs missing them | DescriptionFilterChain |
| 3 | DescriptionFilterChain.refilter | Delegates to JobFilterChain for lang+yoe; handles PENDING visa re-evaluation separately | Persist |
| 4 | BackfillPostProcessors | Run post-backfill hooks | Complete |

**Error Flows**:
- Strategy fetch exception → `FetchResult.error()` → endpoint marked ERROR, consecutiveErrors incremented, auto-deactivated at 10
- Rate limit → endpoint marked RATE_LIMITED, no error count increment, retried next cycle
- Filter chain exception → fail-open (KEEP), logged as warning
- Virtual thread timeout → Semaphore blocks new tasks but doesn't kill running ones; HikariCP connection timeout handles DB stalls
- Bulk deactivate race → prevented by Semaphore (one thread per endpoint) + unique endpoint assignment

## Data Model

### New/Modified Entities

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| `JobPosting` (existing) | All existing fields unchanged | endpoint, company, matchScore, opportunityScore | Unique: (source, externalId) |

### New Indexes (Liquibase Migration)

| Index | Table | Columns | Condition | Purpose |
|-------|-------|---------|-----------|---------|
| `idx_job_endpoint_active` | job_posting | (endpoint_id, is_active) | — | Fast lookup for deactivation queries |
| `idx_job_visa_pending` | job_posting | (visa_sponsorship, discovered_date) | `WHERE visa_sponsorship = 'PENDING'` | Partial index for visa reaper scheduler |
| `idx_job_source_active` | job_posting | (source, is_active, language_filter) | — | Fast unscored-by-source query |

### Scoring Query Rewrites

**Before** (correlated NOT IN subquery):
```sql
SELECT j FROM JobPosting j
WHERE j.isActive = true AND j.languageFilter = :filter
  AND j.id NOT IN (SELECT ms.job.id FROM MatchScore ms)
```

**After** (LEFT JOIN anti-join):
```sql
SELECT j FROM JobPosting j
  LEFT JOIN MatchScore ms ON ms.job.id = j.id
  LEFT JOIN FETCH j.company
WHERE j.isActive = true AND j.languageFilter = :filter
  AND ms.id IS NULL
```

Applied to all 3 scoring queries: `findUnscoredActiveJobs`, `findUnscoredActiveJobsByEndpoint`, `findUnscoredActiveJobsBySource`.

### Bulk Deactivate Query

**Before** (N+1 in loop):
```java
for (JobPosting job : activeJobs) {
    if (!seenExternalIds.contains(job.getExternalId())) {
        job.setActive(false);
        jobPostingRepository.save(job);  // N writes
    }
}
```

**After** (single @Modifying query):
```sql
UPDATE job_posting
SET is_active = false, deactivated_at = :now
WHERE endpoint_id = :endpointId
  AND is_active = true
  AND external_id NOT IN (:seenExternalIds)
```

## Thread Model

```
┌──────────────────────────────────────────────────────────────────┐
│                    Virtual Thread Executor                         │
│              (newVirtualThreadPerTaskExecutor)                     │
│                                                                   │
│   VT-1 ──┐                                                       │
│   VT-2 ──┼── Semaphore(20) ──┐                                   │
│   VT-3 ──┤                   │                                    │
│   ...    ─┤                   ▼                                   │
│   VT-N ──┘         ┌──────────────────────┐                      │
│                     │ HikariCP Pool (20)   │                      │
│                     │ max-pool-size: 20    │                      │
│                     │ connection-timeout:  │                      │
│                     │   30000ms            │                      │
│                     └──────────┬───────────┘                      │
│                                │                                  │
│                                ▼                                  │
│                     ┌──────────────────────┐                      │
│                     │   PostgreSQL 16       │                      │
│                     │   max_connections:    │                      │
│                     │   100 (default)       │                      │
│                     └──────────────────────┘                      │
└──────────────────────────────────────────────────────────────────┘

Invariants:
  - Semaphore permits == HikariCP max-pool-size (both 20)
  - Each virtual thread holds ≤1 DB connection during @Transactional
  - HTTP fetch happens OUTSIDE transaction (no connection held during I/O wait)
  - Spring @Transactional uses thread-local — safe with virtual threads
    (each VT has own carrier thread context)
```

**Configuration** (`application.yaml`):
```yaml
crawl:
  concurrency: 20

spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 30000
```

## Behavioral Invariant Verification Matrix

| Scenario | Location | Visa Filter | Expected Decision | Mechanism |
|----------|----------|-------------|-------------------|-----------|
| Berlin, Germany | DE match | SKIPPED | KEEP (visa=null) | `VisaSponsorshipFilter.extractCountry` → "germany" → bypass |
| Munich, Bavaria, Germany | DE match | SKIPPED | KEEP (visa=null) | DE pattern `\bmunich\b` matches |
| Amsterdam, Netherlands | EU target | ACTIVE | KEEP if CONFIRMED/LIKELY, SKIP if REJECTED/UNKNOWN | Detection chain runs on description |
| Remote - EU | Remote-EU match | ACTIVE | KEEP if CONFIRMED/LIKELY, SKIP if REJECTED/UNKNOWN | `matchesRemoteEu()` triggers detection |
| Aggregator + NL + no description | EU target, aggregator=true | DEFERRED | KEEP (visa=PENDING) | `isAggregator && description.length < 200` → PENDING |
| No visa signal found | EU target | `unknownAction: skip` | SKIP (visa=UNKNOWN) | Config `unknown-action: skip` |
| Location not in any list | Unknown | SKIPPED | Location filter decides | `extractCountry()` → null, not remote-EU → bypass |
| Visa-exempt source (expat portal) | Any | SKIPPED | KEEP (visa=LIKELY) | `visaExempt=true` → chain sets LIKELY, skips detection |

**JobFilterChain preserves these invariants by**:
1. Accepting `visaExempt` flag from caller (true for expat sources)
2. Delegating to `VisaSponsorshipFilter.filter(location, description, isAggregator)` which internally handles DE bypass
3. Never reordering filters — visa always runs after location confirms the job is in a target region
4. `DescriptionFilterChain.refilter()` calls visa with `isAggregator=false` to force detection (no deferral)

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Virtual threads over platform thread pool | `Executors.newVirtualThreadPerTaskExecutor()` | Java 21 native, no tuning, unbounded lightweight threads | Fixed thread pool (current: 3), CompletableFuture pool | Virtual threads pin on synchronized blocks (not used here); simpler code |
| Semaphore over thread pool sizing | `Semaphore(20)` + unlimited VTs | Decouples concurrency limit from thread lifecycle; easily configurable | Fixed VT count, rate limiter | Semaphore is simple but doesn't handle backpressure/queueing |
| LEFT JOIN over NOT IN | Anti-join pattern | PostgreSQL optimizer handles LEFT JOIN IS NULL better for large tables; avoids materialization of subquery | EXISTS subquery, EXCEPT | LEFT JOIN can be less readable; EXPLAIN validation needed |
| Single JobFilterChain over per-caller chains | One service, flags for behavior variants | Eliminates code duplication (CrawlService + Aggregator had 80% identical logic) | Separate chains per context, strategy pattern per pipeline | Single chain must handle both contexts via boolean flags; slightly less pure |
| HTTP fetch outside @Transactional | Split crawlEndpoint into fetch + persist | Holding DB connection during 45s HTTP timeout exhausts pool | Keep current (single @Transactional) | Two-method split, slightly more complex error handling |
| Template Method for ATS strategies | AbstractAtsStrategy base class with protected helpers | 14 strategies duplicate ~5 identical methods (elapsed, truncate, stripHtml) | Utility class with static methods, mixin interface (default methods) | Inheritance coupling; escape hatch via direct FetchStrategy for outliers |
| Batch dedup pre-load over per-job queries | Load all externalIds/fingerprints into Set before loop | Eliminates N DB roundtrips per ingestion cycle | Database upsert with ON CONFLICT, batch IN queries | Memory: ~50K strings × ~50 bytes = ~2.5MB per source (acceptable) |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Virtual threads + HikariCP exhaustion | Crawl stalls, connection timeout errors | Medium | Semaphore permits = pool size (20); metrics alert on pool wait time |
| Filter chain reordering changes decisions | Jobs that passed before get filtered, or vice versa | Low | Unit test corpus from production data; verify same outputs before/after |
| LEFT JOIN query plan regression | Scoring queries slower on edge cases | Low | Run EXPLAIN ANALYZE on prod-sized data; migration is reversible |
| Bulk deactivate conflict on concurrent crawl | Two threads deactivate same endpoint's jobs | Low | Semaphore ensures one thread per endpoint; endpoints assigned uniquely |
| AbstractAtsStrategy doesn't fit all strategies | Workday (pagination), SuccessFactors (custom auth) need overrides | Medium | Keep `fetch()` as abstract override point; base class provides helpers, not template |
| Batch dedup memory on large sources | Indeed returns 5000+ jobs; holding all fingerprints in memory | Low | 5000 fingerprints × 16 chars = ~80KB; well within heap |
| Spring @Transactional thread-local with virtual threads | Transaction context lost on continuation switch | Very Low | Spring 6.1+ (in Boot 3.3.5) explicitly supports virtual threads; ThreadLocal propagation verified |

## Test Plan

### Unit Tests

**JobFilterChain**:
- Happy path: all filters pass → KEEP with null reason
- Language filter SKIP → short-circuit, no subsequent filters called
- Role filter SKIP → short-circuit
- Location filter SKIP → short-circuit
- Visa filter SKIP (EU country, no sponsorship signal, unknown-action=skip) → SKIP with visa reason
- Visa filter bypass (Germany location) → KEEP, visaSponsorship=null
- Visa filter PENDING (aggregator + EU + short description) → KEEP, visaSponsorship=PENDING
- YOE filter SKIP (requires 10+ years, max=5) → SKIP
- visaExempt=true → visa step skipped, KEEP with visa=LIKELY
- Null/blank inputs → graceful handling (no NPE)
- Filter exception → fail-open KEEP, logged

**AbstractAtsStrategy**:
- `elapsed()` returns positive duration
- `truncate(null)` → null, `truncate("abc", 2)` → "ab"
- `stripHtml("<p>text</p>")` → "text"
- `parseIsoDate` valid ISO → LocalDate, invalid → null
- `textOrNull` blank → null, present → text
- `safeExecute` on exception → FetchResult.error
- `mapArray` with null elements → filtered out

**ScoringService**:
- `scoreAllUnscored` with empty page → no-op
- `scoreAllUnscored` multi-page → processes all pages
- `scoreJobsForEndpoint` delegates to MatchScoringService + OpportunityScoringService
- Exception in scoring → logged, not thrown

**Bulk deactivate**:
- 0 seen IDs → all active jobs deactivated
- All seen → no deactivation
- Partial → only missing ones deactivated

**Mocks**: All filter beans mocked in JobFilterChain tests. WebClient mocked in strategy tests. Repository mocked in service tests.

### Integration Tests

**Crawl pipeline (Testcontainers PostgreSQL)**:
- Parallel crawl of 5 mock endpoints → all persist correctly, no data corruption
- Transaction isolation: concurrent endpoint crawls don't interfere
- Bulk deactivate produces correct active/inactive state
- Scoring queries with LEFT JOIN return same results as old NOT IN queries (regression)
- New indexes are used (EXPLAIN ANALYZE in test)

**Aggregator batch dedup**:
- Pre-loaded externalId set correctly skips known jobs
- Fingerprint match correctly enriches existing ATS jobs
- Large batch (1000 jobs) processes within 10s

**Filter chain integration**:
- End-to-end: raw job → filter chain → correct decision for each invariant scenario (Germany/EU/Remote/Unknown)

### End-to-End Tests

**Full pipeline cycle**:
- Trigger PipelineScheduler → crawl + ingest + score
- Verify: new jobs appear with correct filter decisions, scores populated
- Verify: jobs removed from ATS are soft-deleted
- Verify: PENDING visa jobs get re-evaluated after backfill

**Performance acceptance**:
- 100 mock endpoints crawled in < 2 minutes (parallel)
- Scoring pass for 1000 unscored jobs completes in < 30s
- No N+1 queries in hot paths (assert query count with Hibernate statistics)

### Non-Functional Tests

**Performance**:
- Crawl cycle throughput: ≥ 50 endpoints/minute with 20 concurrent
- Scoring query < 100ms per batch of 200 (with new indexes)
- Aggregator ingestion < 5s for 500 jobs (with batch dedup)

**Connection pool safety**:
- Under load (20 concurrent crawls), HikariCP never reports connection timeout
- Pool utilization stays < 80% (metrics assertion)

**Thread safety**:
- ScoringService methods are stateless (verify no shared mutable state)
- CrawlService parallel execution: no ConcurrentModificationException on shared stats

## Phase Mapping

| Phase | Components Affected | Architectural Change |
|-------|--------------------|--------------------|
| 1: Safety Fixes | LeverStrategy, FetchStrategy interface, TeamtailorStrategy | Timeout on `.block()`, `supportedTypes()` replaces `supports()` |
| 2: Transaction & DB | CrawlService, JobPostingRepository, Liquibase | Split fetch/persist, LEFT JOIN queries, bulk deactivate, new indexes |
| 3: Filter Chain | JobFilterChain (new), RawJobInput (new), CrawlService, AggregatorIngestionServiceImpl, DescriptionFilterChain | Unified chain replaces duplicated logic |
| 4: Abstract Strategy | AbstractAtsStrategy (new), all 14 ATS strategies | Template method base class, deduplicated helpers |
| 5: Batch Dedup | AggregatorIngestionServiceImpl, JobPostingRepository | Pre-load Sets, eliminate per-job DB queries |
| 6: Parallelism | CrawlService, PipelineScheduler, application.yaml | Virtual threads, Semaphore, configurable concurrency |
| 7: Layer Decoupling | ScoringService (new), ScoringScheduler, CrawlService, AdminController | Extract scoring logic, thin schedulers, move admin logic to services |
