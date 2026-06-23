# Plan: API Module Contracts & Performance

## Overview

Consolidate the API module into a clean contract-based architecture. Extract duplicated filter chains, strategy boilerplate, and ingestion logic into shared abstractions. Fix critical performance bottlenecks in the crawl pipeline (sequential execution, transaction boundaries, N+1 queries).

## Tech Stack

- Java 21 (virtual threads for parallelism)
- Spring Boot 3.3.5
- PostgreSQL 16 (index additions via Liquibase)
- Existing: WebClient, Quartz, JPA/Hibernate

## Testing Strategy

- Unit: each new contract/base class (JobFilterChain, AbstractAtsStrategy, ScoringService)
- Integration: crawl pipeline end-to-end with Testcontainers (parallelism + transaction correctness)
- Done when: `./gradlew test` passes, no N+1 queries in hot paths, crawl cycle <2 min for 100 endpoints

## Behavioral Invariants (must preserve)

- Germany-based jobs: pass through profile filters (lang, role, location, YOE, dedup) — visa filter is skipped (domestic)
- EU target-country jobs (NL, AT, CH, IE, SE, DK, FI, ES): pass through profile filters + visa sponsorship filter — only jobs with explicit sponsorship signals survive
- Remote-EU jobs: same as EU target-country (visa filter active)
- Pipeline crawls ALL active non-CUSTOM endpoints every cycle (no frequency gating)
- AI crawl (CUSTOM endpoints) runs every 8h, batch of 150
- Aggregator pipeline runs every 4h, all enabled sources
- `unknown-action: skip` for visa filter — silence = dropped for non-DE jobs
- DescriptionBackfillers re-run filters on PENDING visa jobs after description arrives

## Phases

### Phase 1: Critical One-Liners & Safety Fixes

- Step 1: Fix LeverStrategy `.block()` — add `Duration.ofSeconds(45)` timeout
- Step 2: Fix StrategyRegistry — change to `Set<AtsType> supportedTypes()` on FetchStrategy interface
- Step 3: Fix TeamtailorStrategy — remove redundant `@Autowired` annotation

### Phase 2: Transaction Boundary & DB Performance

- Step 1: Split `CrawlService.crawlEndpoint()` — extract HTTP fetch outside `@Transactional`, keep only `persistFetchResult()` transactional
- Step 2: Replace `NOT IN (SELECT...)` with `LEFT JOIN ... IS NULL` in all 3 scoring queries in `JobPostingRepository`
- Step 3: Add bulk deactivate `@Modifying` query — replace N+1 writes in `deactivateMissingJobs()`
- Step 4: Add Liquibase migration with missing indexes:
  - `idx_job_endpoint_active ON job_posting(endpoint_id, is_active)`
  - `idx_job_visa_pending ON job_posting(visa_sponsorship, discovered_date) WHERE visa_sponsorship = 'PENDING'`
  - `idx_job_source_active ON job_posting(source, is_active, language_filter)`

### Phase 3: Unified Filter Chain

- Step 1: Extract `YoeFilter` interface from concrete class — `filter(Integer yoe): FilterResult` + `extractYoe(String description): Integer`
- Step 2: Extract `VisaSponsorshipFilter` interface from concrete class — `filter(String location, String description, boolean isAggregator): VisaFilterResult`
- Step 3: Extract `DeduplicationFilter` interface from concrete class — `generateFingerprint(String title, String company, String location): String`
- Step 4: Rename concrete classes to `YoeFilterImpl`, `VisaSponsorshipFilterImpl`, `DeduplicationFilterImpl`
- Step 5: Create `filter/JobFilterChain.java` — single service wrapping lang→role→location→visa→yoe with `apply(RawJobInput, boolean isAggregator, boolean visaExempt)` returning `FilterChainResult`
- Step 6: Create `filter/RawJobInput` record — title, description, location (decouples from entity)
- Step 7: Refactor `CrawlService.processFetchResult()` — replace nested-if pyramid with `jobFilterChain.apply()`
- Step 8: Refactor `AggregatorIngestionServiceImpl.applyFilters()` — replace early-return body with `jobFilterChain.apply()`
- Step 9: Refactor `DescriptionFilterChain` — delegate to `JobFilterChain` for lang+yoe, keep visa PENDING re-evaluation as its own concern
- Step 10: Delete `DescriptionFilterChain` inline filter logic (now in `JobFilterChain`)

### Phase 4: Abstract ATS Strategy Base Class

- Step 1: Create `strategy/ats/AbstractAtsStrategy.java` — provides `elapsed()`, `truncate()`, `parseIsoDate()`, `stripHtml()`, `textOrNull()`, `safeExecute()`, `mapArray()`
- Step 2: Migrate `GreenhouseStrategy` to extend `AbstractAtsStrategy` (pilot — validate approach)
- Step 3: Migrate remaining 12 ATS strategies: Lever, Ashby, SmartRecruiters, Workable, Workday, Personio, Breezy, Recruitee, JOIN, BambooHR, Teamtailor, SuccessFactors
- Step 4: Delete duplicated private helper methods from all migrated strategies

### Phase 5: Batch Dedup in Aggregator Ingestion

- Step 1: Refactor `AggregatorIngestionServiceImpl.ingest()` — call `findExternalIdsBySource()` once before loop, skip known IDs without per-job DB query
- Step 2: Batch fingerprint check — collect fingerprints, do single `WHERE fingerprint IN (...)` query before loop
- Step 3: Remove per-job `findBySourceAndExternalId()` and `findAtsJobByFingerprint()` calls from inner loop

### Phase 6: Parallelize Crawl Pipeline

- Step 1: Refactor `crawlAllDueEndpoints()` — use `Executors.newVirtualThreadPerTaskExecutor()` (Java 21)
- Step 2: Add configurable concurrency limit via `application.yaml` (`crawl.concurrency: 20`)
- Step 3: Use `Semaphore` to cap concurrent endpoint crawls
- Step 4: Replace `PipelineScheduler` fixed thread pool (3) with virtual threads or configurable pool
- Step 5: Ensure per-endpoint scoring (`scoreJobsForEndpoint`) is thread-safe (already stateless — verify)

### Phase 7: Layer Decoupling

- Step 1: Extract `ScoringService` from `ScoringScheduler` — move `scoreAllUnscored()`, `scoreJobsForEndpoint()`, `scoreJobsForSource()` logic into service
- Step 2: `ScoringScheduler` becomes a thin Quartz job that calls `ScoringService`
- Step 3: `CrawlService` depends on `ScoringService` (not scheduler)
- Step 4: `ScoringPostProcessor` calls `ScoringService` (not scheduler)
- Step 5: Move AdminController business logic (refilter, health assembly) into dedicated services

## Risks/Edge cases

- **Virtual threads + `@Transactional`**: Spring's thread-local transaction binding works with virtual threads, but need to verify connection pool doesn't exhaust under load — add HikariCP `maximum-pool-size: 20` config
- **Bulk deactivate race condition**: if two crawl threads process the same endpoint concurrently, bulk deactivate could conflict — Semaphore + unique endpoint assignment prevents this
- **Filter chain ordering change**: if `JobFilterChain` subtly reorders checks vs current nested-if, jobs might get different decisions — must validate with existing test corpus
- **AbstractAtsStrategy migration**: each strategy has quirks (Workday pagination, SmartRecruiters detail fetch) — some won't fit cleanly into `safeExecute()` template, keep escape hatch for custom `fetch()` override
- **NOT IN → LEFT JOIN migration**: verify EXPLAIN plans on production-sized data before deploying — add as a reversible migration
- **Lever timeout fix**: `.block(Duration.ofSeconds(45))` throws `IllegalStateException` on timeout — existing error handling in `processFetchResult` already catches this, but verify
