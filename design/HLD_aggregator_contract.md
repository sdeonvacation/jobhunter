# HLD: Unified Aggregator Contract

## Tech Stack

| Category  | Technology       | Purpose                                              |
| --------- | ---------------- | ---------------------------------------------------- |
| Language  | Java 21          | Project standard, records for immutable data types   |
| Framework | Spring Boot 3.3.5| DI, conditional beans, config binding                |
| ORM       | Hibernate 6.5    | JPA entity management, enum mapping                  |
| DB        | PostgreSQL 16    | Persistence, VARCHAR enum columns                    |
| Migration | Liquibase        | Schema evolution, data migration for source column   |
| Scheduler | Quartz 2.3       | Pipeline orchestration, per-source scheduling        |
| HTTP      | WebClient        | Non-blocking HTTP for REST API strategies            |
| Testing   | JUnit 5 + WireMock 3.5 | Unit tests with mocked transports             |

## Components

| Component                  | Responsibility                                          | Dependencies                                      |
| -------------------------- | ------------------------------------------------------- | ------------------------------------------------- |
| FetchStrategy              | Universal fetch interface for all source types           | FetchContext, FetchResult                          |
| FetchContext               | Immutable input carrier (endpoint OR search params)      | CareerEndpoint, config map                         |
| FetchResult                | Immutable output with jobs + metadata                    | RawAggregatorJob record                           |
| SourceConfig               | Aggregator source definition (type, strategy, schedule)  | FetchStrategy, JobSource, DiscoverySource          |
| StrategyRegistry           | Routes AtsType/source-name → correct FetchStrategy      | List<FetchStrategy>, Map<AtsType, FetchStrategy>   |
| AggregatorIngestionService | Orchestrates fetch → dedup → filter → resolve → persist | SourceConfig, filters, repositories                |
| IngestionStats             | Result record for ingestion operations                   | None (pure data)                                   |
| JobSource enum             | Universal origin tracking on JobPosting                  | None                                               |
| DynamicSourceConfigLoader  | Reads `aggregator.sources[]` from YAML, creates SourceConfig instances at startup | StrategyRegistry, application.yaml config          |
| CrawlService (modified)    | Uses StrategyRegistry instead of JobExtractorRegistry    | StrategyRegistry, filters, repositories            |
| PipelineScheduler (modified)| Iterates SourceConfig beans for aggregator execution    | List<SourceConfig>, AggregatorIngestionService     |

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        PipelineScheduler                              │
│  [crawl ATS/Direct]  [iterate SourceConfig beans in parallel]        │
└────────┬──────────────────────────────┬──────────────────────────────┘
         │                              │
         ▼                              ▼
┌─────────────────────┐    ┌──────────────────────────────┐
│    CrawlService     │    │  AggregatorIngestionService   │
│  (endpoint-scoped)  │    │  (search-scoped)             │
└────────┬────────────┘    └──────────────┬───────────────┘
         │                                │
         ▼                                ▼
┌─────────────────────────────────────────────────────────┐
│                    StrategyRegistry                       │
│  routes AtsType → strategy  OR  sourceName → strategy    │
└────────────────────────────┬────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────────┐   ┌────────────────────┐
│  strategy/   │   │  strategy/       │   │  strategy/         │
│  ats/        │   │  direct/         │   │  aggregator/       │
│              │   │                  │   │                    │
│ Greenhouse   │   │ AiPageStrategy   │   │ McpStrategy        │
│ Lever        │   │                  │   │ CliStrategy        │
│ Workday      │   │                  │   │ AiAggregatorStrat. │
│ Ashby        │   │                  │   │ RestApiStrategy    │
│ ... (13)     │   │                  │   │                    │
└──────────────┘   └──────────────────┘   └────────────────────┘
         │                   │                       │
         └───────────────────┴───────────────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │  Shared Filter Chain          │
              │  Language → Role → Location   │
              │  → YOE → Deduplication        │
              └──────────────────────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │  JobPostingRepository         │
              │  CompanyRepository            │
              └──────────────────────────────┘

              ┌──────────────────────────────┐
              │  DynamicSourceConfigLoader    │
              │  (startup bean)              │
              │                              │
              │  Reads aggregator.sources[]  │
              │  from application.yaml       │
              │  Creates SourceConfig beans  │
              │  for non-dedicated sources   │
              └──────────────────────────────┘
                             │
                             ▼
              Registers into List<SourceConfig>
              alongside LinkedInSource, IndeedSource
```

**Description**: PipelineScheduler runs two parallel tracks: CrawlService for endpoint-based sources (ATS + Direct, company known from endpoint), and AggregatorIngestionService for search-based sources (company must be resolved per-job). Both tracks obtain their FetchStrategy from the shared StrategyRegistry. All strategies implement the same `FetchStrategy` interface, returning `FetchResult`. The filter chain is reused identically by both CrawlService and AggregatorIngestionService. StrategyRegistry auto-populates from Spring-managed strategy beans.

**Source registration model**: LinkedIn and Indeed retain dedicated `@Component` classes (`LinkedInSource`, `IndeedSource`) due to complex wiring (MCP client, rate limiter, CLI subprocess). All other aggregators are declared in `aggregator.sources[]` YAML and instantiated by `DynamicSourceConfigLoader` at startup — zero Java code required for new sources.

## Interfaces

### FetchStrategy

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `fetch(FetchContext)` | `FetchContext ctx` | `FetchResult` | Execute fetch with pagination (respects maxPages/maxResults), return all discovered jobs | Returns `FetchResult.error(message, elapsed)` on failure |
| `supports(AtsType)` | `AtsType type` | `boolean` | Whether this strategy handles the given ATS type (for ATS strategies only) | N/A |
| `name()` | none | `String` | Unique strategy identifier (e.g. "greenhouse", "linkedin-mcp") | N/A |

```java
package dev.jobhunter.strategy;

import dev.jobhunter.model.enums.AtsType;

public interface FetchStrategy {
    FetchResult fetch(FetchContext context);
    boolean supports(AtsType type);
    String name();
}
```

### FetchContext

| Method/Field | Type | Purpose | Nullable |
|--------------|------|---------|----------|
| `endpoint` | `CareerEndpoint` | Source endpoint (ATS/Direct) | Yes (null for aggregators) |
| `keywords` | `List<String>` | Search terms (aggregators) | Yes (null for ATS) |
| `locations` | `List<String>` | Location filters (aggregators) | Yes (null for ATS) |
| `maxResults` | `int` | Cap per fetch (default 50) | No |
| `maxPages` | `int` | Pagination limit (default 3) | No |
| `config` | `Map<String, Object>` | Source-specific config (rate limits, URLs, etc.) | No (empty map default) |

```java
package dev.jobhunter.strategy;

import dev.jobhunter.model.CareerEndpoint;
import java.util.List;
import java.util.Map;

public record FetchContext(
    CareerEndpoint endpoint,
    List<String> keywords,
    List<String> locations,
    int maxResults,
    int maxPages,
    Map<String, Object> config
) {
    /** Factory for ATS/Direct endpoint-based fetch */
    public static FetchContext forEndpoint(CareerEndpoint endpoint) {
        return new FetchContext(endpoint, null, null, 200, 10, Map.of());
    }

    /** Factory for aggregator search-based fetch */
    public static FetchContext forSearch(List<String> keywords, List<String> locations,
                                         int maxResults, int maxPages, Map<String, Object> config) {
        return new FetchContext(null, keywords, locations, maxResults, maxPages, config);
    }
}
```

### FetchResult

| Method/Field | Type | Purpose | Nullable |
|--------------|------|---------|----------|
| `jobs` | `List<RawAggregatorJob>` | Extracted jobs | No (empty on error) |
| `totalFound` | `int` | Total discovered before pagination limit | No |
| `status` | `ExtractionStatus` | SUCCESS, EMPTY, ERROR, RATE_LIMITED, PROTECTED | No |
| `errorMessage` | `String` | Error details | Yes |
| `elapsed` | `Duration` | How long the fetch took | No |

```java
package dev.jobhunter.strategy;

import dev.jobhunter.model.enums.ExtractionStatus;
import java.time.Duration;
import java.util.List;

public record FetchResult(
    List<RawAggregatorJob> jobs,
    int totalFound,
    ExtractionStatus status,
    String errorMessage,
    Duration elapsed
) {
    public static FetchResult success(List<RawAggregatorJob> jobs, Duration elapsed) {
        return new FetchResult(jobs, jobs.size(), ExtractionStatus.SUCCESS, null, elapsed);
    }

    public static FetchResult empty(Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.EMPTY, null, elapsed);
    }

    public static FetchResult error(String message, Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.ERROR, message, elapsed);
    }

    public static FetchResult rateLimited(Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.RATE_LIMITED,
            "Rate limited - will retry next cycle", elapsed);
    }
}
```

### RawAggregatorJob

| Field | Type | Purpose | Nullable |
|-------|------|---------|----------|
| `externalId` | `String` | Unique ID within the source | No |
| `title` | `String` | Job title | No |
| `companyName` | `String` | Employer name (null for ATS/Direct — company from endpoint) | Yes |
| `location` | `String` | Raw location string | Yes |
| `description` | `String` | Full job description | Yes |
| `applyUrl` | `String` | Application link | Yes |
| `postedDate` | `LocalDate` | When job was posted | Yes |
| `salaryMin` | `BigDecimal` | Salary range lower bound | Yes |
| `salaryMax` | `BigDecimal` | Salary range upper bound | Yes |
| `salaryCurrency` | `String` | ISO currency code | Yes |
| `rawJson` | `String` | Original JSON for debugging | Yes |

```java
package dev.jobhunter.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawAggregatorJob(
    String externalId,
    String title,
    String companyName,    // null → company from endpoint; non-null → resolve
    String location,
    String description,
    String applyUrl,
    LocalDate postedDate,
    BigDecimal salaryMin,
    BigDecimal salaryMax,
    String salaryCurrency,
    String rawJson
) {}
```

### SourceConfig

| Method | Return Type | Purpose |
|--------|-------------|---------|
| `name()` | `String` | Unique source identifier (e.g. "linkedin", "indeed", "bsj") |
| `sourceType()` | `JobSource` | Enum value for origin tracking |
| `discoverySource()` | `DiscoverySource` | For company.discovered_via when creating new companies |
| `strategy()` | `FetchStrategy` | The strategy bean to use |
| `buildContext()` | `FetchContext` | Builds FetchContext from config/profile |
| `frequencyHours()` | `int` | How often to run (from application.yaml) |
| `isEnabled()` | `boolean` | Whether this source is active |

**Two-tier implementation model**:
- **Dedicated classes** (LinkedIn, Indeed): `@Component` beans with complex constructor injection (MCP client, rate limiter, CLI subprocess). These need custom wiring that YAML cannot express.
- **YAML-driven instances** (BSJ, Arbeitnow, any future source): Created by `DynamicSourceConfigLoader` from `aggregator.sources[]` config. No Java class per source. Strategy resolved by name from StrategyRegistry.

```java
package dev.jobhunter.source;

import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;

public interface SourceConfig {
    String name();
    JobSource sourceType();
    DiscoverySource discoverySource();
    FetchStrategy strategy();
    FetchContext buildContext();
    int frequencyHours();
    boolean isEnabled();
}
```

### DynamicSourceConfigLoader

Reads `aggregator.sources[]` from application.yaml at startup and produces `SourceConfig` instances:

```java
package dev.jobhunter.source;

import dev.jobhunter.ingestion.StrategyRegistry;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class DynamicSourceConfigLoader {

    @Bean
    public List<SourceConfig> dynamicSources(
            AggregatorSourceProperties props,
            StrategyRegistry registry) {

        return props.getSources().stream()
            .map(entry -> new YamlSourceConfig(
                entry.getName(),
                JobSource.valueOf(entry.getJobSource()),
                DiscoverySource.valueOf(entry.getDiscoverySource()),
                registry.getStrategy(entry.getStrategy())
                    .orElseThrow(() -> new IllegalStateException(
                        "No strategy named: " + entry.getStrategy())),
                entry.getUrl(),
                entry.getFrequencyHours(),
                entry.getMaxResults()
            ))
            .toList();
    }
}
```

```java
@ConfigurationProperties(prefix = "aggregator")
public class AggregatorSourceProperties {
    private List<SourceEntry> sources = List.of();

    // getters/setters

    public static class SourceEntry {
        private String name;
        private String strategy;      // maps to FetchStrategy.name()
        private String jobSource;     // JobSource enum name
        private String discoverySource; // DiscoverySource enum name
        private String url;
        private int frequencyHours;
        private int maxResults;
        // getters/setters
    }
}
```

### StrategyRegistry

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `getStrategy(AtsType)` | `AtsType type` | `Optional<FetchStrategy>` | Find strategy supporting given ATS type | Empty optional if none registered |
| `getStrategy(String)` | `String name` | `Optional<FetchStrategy>` | Find strategy by name | Empty optional if none registered |
| `supportedTypes()` | none | `Set<AtsType>` | All registered ATS types | N/A |

```java
package dev.jobhunter.ingestion;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchStrategy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {

    private final Map<AtsType, FetchStrategy> byType;
    private final Map<String, FetchStrategy> byName;

    public StrategyRegistry(List<FetchStrategy> strategies) {
        this.byName = strategies.stream()
            .collect(Collectors.toMap(FetchStrategy::name, s -> s));
        this.byType = new HashMap<>();
        for (FetchStrategy s : strategies) {
            for (AtsType t : AtsType.values()) {
                if (s.supports(t)) byType.put(t, s);
            }
        }
    }

    public Optional<FetchStrategy> getStrategy(AtsType type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Optional<FetchStrategy> getStrategy(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<AtsType> supportedTypes() {
        return Collections.unmodifiableSet(byType.keySet());
    }
}
```

### AggregatorIngestionService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `ingest(SourceConfig)` | source config | `IngestionStats` | Full pipeline: fetch → dedup → filter → company resolve → persist | Catches transport errors, returns stats with error count |

```java
package dev.jobhunter.ingestion;

import dev.jobhunter.source.SourceConfig;

public interface AggregatorIngestionService {
    IngestionStats ingest(SourceConfig source);
}
```

### IngestionStats

```java
package dev.jobhunter.ingestion;

public record IngestionStats(
    String sourceName,
    int fetched,
    int enriched,       // matched existing ATS job, added external link
    int created,        // new JobPosting persisted
    int filtered,       // rejected by filter chain
    int duplicates,     // skipped by dedup
    int errors,         // per-job processing errors
    long elapsedMs
) {}
```

## Data Flow

### Aggregator Ingestion Flow (fetch → dedup → filter → resolve → persist)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineScheduler | Iterates `List<SourceConfig>`, checks `isEnabled()` + `frequencyHours()` due | AggregatorIngestionService |
| 2 | AggregatorIngestionService | Calls `source.strategy().fetch(source.buildContext())` | FetchResult |
| 3 | FetchStrategy impl | Executes transport-specific fetch (MCP/CLI/HTTP/AI), handles pagination | Returns FetchResult |
| 4 | AggregatorIngestionService | For each job: check `existsBySourceAndExternalId(source, externalId)` | Skip if exists |
| 5 | AggregatorIngestionService | Generate fingerprint via DeduplicationFilter | Check for ATS enrichment match |
| 6 | AggregatorIngestionService | If fingerprint matches existing ATS job → enrich (add external link) | enriched++ |
| 7 | AggregatorIngestionService | If no match → apply filter chain: Language → Role → Location → YOE → Dedup | filtered++ or continue |
| 8 | AggregatorIngestionService | Resolve company: `companyRepository.findByNormalizedName()` or create new | Company entity |
| 9 | AggregatorIngestionService | Build JobPosting, set `source = sourceConfig.sourceType()`, persist | created++ |
| 10 | AggregatorIngestionService | Return IngestionStats | PipelineScheduler logs |

```
┌──────────────┐     ┌──────────────────────┐     ┌──────────────────┐
│SourceConfig  │────▶│AggregatorIngestion   │────▶│ FetchStrategy    │
│ .buildContext│     │Service.ingest()      │     │ .fetch(ctx)      │
└──────────────┘     └──────────┬───────────┘     └────────┬─────────┘
                                │                           │
                                │◀── FetchResult ───────────┘
                                │
                     ┌──────────▼───────────┐
                     │ For each job:        │
                     │ 1. existsBySourceId? ─┼──▶ SKIP (already seen)
                     │ 2. fingerprint match?─┼──▶ ENRICH existing (add link)
                     │ 3. filter cascade    ─┼──▶ SKIP (filtered)
                     │ 4. resolve company   ─┼──▶ find-or-create Company
                     │ 5. persist JobPosting │
                     └──────────────────────┘
```

### ATS Crawl Flow (showing how existing extractors become strategies)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineScheduler | Triggers `CrawlService.crawlAllDueEndpoints()` | CrawlService |
| 2 | CrawlService | Queries endpoints due for crawl (time-based) | Iterates endpoints |
| 3 | CrawlService | `strategyRegistry.getStrategy(endpoint.getAtsType())` | FetchStrategy |
| 4 | FetchStrategy (ATS) | `fetch(FetchContext.forEndpoint(endpoint))` | FetchResult |
| 5 | CrawlService | Process FetchResult: deactivate missing, upsert found | Filter chain |
| 6 | CrawlService | Apply filters (Language, Role, Location, YOE) | Persist |
| 7 | CrawlService | Save JobPosting with `source = mapToJobSource(endpoint.getAtsType())` | Done |

```
┌──────────────┐     ┌──────────────┐     ┌───────────────────┐
│CrawlService  │────▶│Strategy      │────▶│GreenhouseStrategy │
│.crawlEndpoint│     │Registry      │     │ (or any ATS)      │
│(endpoint)    │     │.getStrategy  │     │ .fetch(ctx)       │
└──────────────┘     │(atsType)     │     └─────────┬─────────┘
                     └──────────────┘               │
                                                    │
       ┌────────────────────────────────────────────┘
       │ FetchResult
       ▼
┌──────────────────────────────┐
│ CrawlService                 │
│ 1. Check status (rate limit?)│
│ 2. Deactivate removed jobs   │
│ 3. Upsert new/updated jobs   │
│ 4. Apply filter chain        │
│ 5. Company = endpoint.company│
│ 6. Persist with JobSource    │
└──────────────────────────────┘
```

**Error Flows**:
- Transport failure (timeout, connection refused): Strategy returns `FetchResult.error(msg, elapsed)`. AggregatorIngestionService increments `errors` in stats, logs, continues to next source.
- Rate limit (429): Strategy returns `FetchResult.rateLimited(elapsed)`. Scheduler marks source as "not due" for its cooldown period.
- Single job parse error: AggregatorIngestionService wraps per-job processing in try-catch, increments `errors`, continues to next job.
- Company resolution failure: Falls back to creating company with status DISCOVERED. Never drops a job due to company resolution failure.

## Data Model

### New: JobSource Enum

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| JobSource (enum) | `GREENHOUSE, LEVER, LEVER_EU, ASHBY, SMARTRECRUITERS, WORKABLE, WORKDAY, WORKDAY_PROTECTED, PERSONIO, BREEZY, RECRUITEE, JOIN, BAMBOOHR, TEAMTAILOR, SUCCESSFACTORS, ICIMS, JOBVITE, STEPSTONE, LINKEDIN, INDEED, BERLIN_STARTUP_JOBS, ARBEITNOW, DIRECT, UNKNOWN` | Maps 1:1 from AtsType for ATS values | String in DB (VARCHAR 50) |

```java
package dev.jobhunter.model.enums;

public enum JobSource {
    // ATS platforms (1:1 from AtsType)
    GREENHOUSE,
    LEVER,
    LEVER_EU,
    ASHBY,
    SMARTRECRUITERS,
    WORKABLE,
    WORKDAY,
    WORKDAY_PROTECTED,
    PERSONIO,
    BREEZY,
    RECRUITEE,
    JOIN,
    BAMBOOHR,
    TEAMTAILOR,
    SUCCESSFACTORS,
    ICIMS,
    JOBVITE,
    STEPSTONE,

    // Aggregator sources
    LINKEDIN,
    INDEED,
    BERLIN_STARTUP_JOBS,
    ARBEITNOW,

    // Direct career pages (AI extraction)
    DIRECT,

    // Fallback
    UNKNOWN;

    /** Convert from legacy AtsType for backward compatibility */
    public static JobSource fromAtsType(AtsType atsType) {
        return switch (atsType) {
            case CUSTOM -> DIRECT;
            case LINKEDIN -> LINKEDIN;
            case INDEED -> INDEED;
            case ARBEITNOW -> ARBEITNOW;
            default -> JobSource.valueOf(atsType.name());
        };
    }
}
```

### Modified: JobPosting Entity

| Entity | Fields Changed | Relationships | Constraints |
|--------|---------------|---------------|-------------|
| JobPosting | `source: AtsType` → `source: JobSource` | No relationship changes | `@Enumerated(EnumType.STRING)`, VARCHAR(50) column unchanged |

### Modified: DiscoverySource Enum (add values)

| Entity | Fields Added | Relationships | Constraints |
|--------|-------------|---------------|-------------|
| DiscoverySource | `BERLIN_STARTUP_JOBS, ARBEITNOW` | Used in `Company.discoveredVia` | Existing LINKEDIN, JOBSPY unchanged |

### Unchanged: AtsType Enum

AtsType remains for `CareerEndpoint.atsType` — it describes the ATS platform of an endpoint. Not deleted, not extended. `LINKEDIN`, `INDEED`, `ARBEITNOW` values remain for backward compat with existing endpoints but `CUSTOM` is the canonical value for direct pages.

## DB Migration Strategy

### Liquibase Changeset: `005-job-source-migration.sql`

```sql
--liquibase formatted sql
--changeset jobhunter:005-job-source-migration

-- Step 1: The source column is already VARCHAR(50) — no type change needed.
-- PostgreSQL stores enum as string. We just need to update values.

-- Step 2: Map legacy AtsType values to new JobSource values
-- CUSTOM → DIRECT (the only mapping that changes name)
UPDATE job_posting SET source = 'DIRECT' WHERE source = 'CUSTOM';

-- Step 3: Add BERLIN_STARTUP_JOBS for any existing BSJ jobs
-- (currently stored under a fake company with source='CUSTOM', already mapped to DIRECT above)
-- If BSJ company exists, update its jobs:
UPDATE job_posting
SET source = 'BERLIN_STARTUP_JOBS'
WHERE company_id IN (
    SELECT id FROM company WHERE normalized_name = 'berlin startup jobs'
)
AND source = 'DIRECT';

-- Step 4: Add index for source-based queries (if not exists)
CREATE INDEX IF NOT EXISTS idx_job_posting_source ON job_posting(source);

-- Step 5: Add last_aggregator_run tracking table
CREATE TABLE aggregator_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_name     VARCHAR(50) NOT NULL UNIQUE,
    last_run_at     TIMESTAMP NOT NULL,
    last_status     VARCHAR(20) NOT NULL,
    jobs_fetched    INTEGER DEFAULT 0,
    jobs_created    INTEGER DEFAULT 0,
    jobs_enriched   INTEGER DEFAULT 0,
    jobs_filtered   INTEGER DEFAULT 0,
    errors          INTEGER DEFAULT 0,
    elapsed_ms      BIGINT DEFAULT 0,
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_aggregator_run_source ON aggregator_run(source_name);
```

**Migration notes**:
- Column type stays `VARCHAR(50)` — no ALTER needed. Hibernate `@Enumerated(EnumType.STRING)` handles the new enum.
- All existing AtsType string values are valid JobSource values (except `CUSTOM` → `DIRECT`).
- The `aggregator_run` table tracks per-source execution history for scheduling decisions.
- Migration is backward-compatible: if rolled back, Java enum just needs the values to exist as strings in the column.

## Spring Wiring Details

### Strategy Registration (Auto-Discovery)

All strategies are `@Component` beans. Spring collects them via constructor injection:

```java
// StrategyRegistry auto-collects all FetchStrategy beans
@Component
public class StrategyRegistry {
    public StrategyRegistry(List<FetchStrategy> strategies) { ... }
}
```

### Conditional Beans for Aggregator Sources

**Dedicated sources** (complex wiring):

```java
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInSource implements SourceConfig {
    // Injects McpStrategy + config properties + rate limiter
}

@Component
@ConditionalOnProperty(prefix = "discovery.providers.jobspy", name = "enabled", havingValue = "true")
public class IndeedSource implements SourceConfig {
    // Injects CliStrategy + config properties + process timeout
}
```

**YAML-driven sources** (no dedicated Java class):

```java
// DynamicSourceConfigLoader creates these at startup from aggregator.sources[] config.
// No @Component per source needed. Example runtime instances:
//   YamlSourceConfig("berlinstartupjobs", BERLIN_STARTUP_JOBS, AiAggregatorStrategy, ...)
//   YamlSourceConfig("arbeitnow", ARBEITNOW, RestApiStrategy, ...)
```

**Merging into unified list** — PipelineScheduler receives all sources:

```java
@Configuration
public class SourceConfigAggregator {
    @Bean
    public List<SourceConfig> allSources(
            List<SourceConfig> dedicatedSources,    // LinkedInSource, IndeedSource
            List<SourceConfig> dynamicSources) {    // from DynamicSourceConfigLoader
        var all = new ArrayList<>(dedicatedSources);
        all.addAll(dynamicSources);
        return Collections.unmodifiableList(all);
    }
}
```

### AggregatorIngestionService Injection

```java
@Service
public class AggregatorIngestionServiceImpl implements AggregatorIngestionService {

    private final JobPostingRepository jobPostingRepository;
    private final CompanyRepository companyRepository;
    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final LocationFilter locationFilter;
    private final YoeFilter yoeFilter;
    private final DeduplicationFilter deduplicationFilter;
    private final AggregatorRunRepository aggregatorRunRepository;

    // Constructor injection, all filters shared with CrawlService
}
```

### PipelineScheduler Wiring

```java
@Component
public class PipelineScheduler implements Job {

    private final CrawlService crawlService;
    private final ScoringScheduler scoringScheduler;
    private final AggregatorIngestionService aggregatorIngestionService;
    private final List<SourceConfig> sources;  // Auto-injected, may be empty

    // replaces: @Autowired LinkedInJobSearchService, IndeedJobSearchService
}
```

## application.yaml Config Structure

```yaml
# Existing (unchanged) — dedicated sources keep their config paths
discovery:
  providers:
    linkedin:
      enabled: ${LINKEDIN_MCP_ENABLED:true}
      keywords: ["backend engineer", "Java developer", "Spring Boot", "Kotlin"]
      locations: ["Germany", "Netherlands", "remote"]
    jobspy:
      enabled: true
      keywords: ["backend engineer", "Java developer", "Spring Boot", "Kotlin"]
      locations: ["Germany", "Netherlands", "remote"]

# Existing (unchanged)
linkedin-mcp:
  enabled: ${LINKEDIN_MCP_ENABLED:true}
  base-url: ${LINKEDIN_MCP_URL:http://localhost:8000}
  # ... rate-limit, circuit-breaker, enrichment config

# NEW: YAML-driven aggregator sources (list format)
aggregator:
  sources:
    - name: berlinstartupjobs
      strategy: ai                    # maps to AiAggregatorStrategy.name()
      job-source: BERLIN_STARTUP_JOBS # JobSource enum value
      discovery-source: BERLIN_STARTUP_JOBS
      url: "https://berlinstartupjobs.com/engineering/"
      frequency-hours: 12
      max-results: 30
    - name: arbeitnow
      strategy: rest-api              # maps to RestApiStrategy.name()
      job-source: ARBEITNOW
      discovery-source: ARBEITNOW
      url: "https://www.arbeitnow.com/api/job-board-api"
      frequency-hours: 6
      max-results: 50
```

**Key principles**:
- LinkedIn and Indeed keep their existing config paths (`linkedin-mcp.*`, `discovery.providers.jobspy.*`) — they have dedicated `@Component` source classes.
- All other aggregators use the `aggregator.sources[]` list — no Java code per source.
- Adding a new aggregator = adding a YAML list entry (if strategy type already exists).
- `strategy` field must match a registered `FetchStrategy.name()` in StrategyRegistry.
- `job-source` and `discovery-source` must be valid enum values (enum values still require Java code to add).

## Error Handling and Retry Strategy

| Error Type | Handling | Retry |
|------------|----------|-------|
| Transport timeout | FetchResult.error(), log, increment `aggregator_run.errors` | Next scheduled run (frequency-hours) |
| HTTP 429 (rate limit) | FetchResult.rateLimited(), mark source as not-due for cooldown | After `circuit-breaker.cooldown-minutes` |
| HTTP 5xx | FetchResult.error(), log | Next scheduled run |
| Connection refused | FetchResult.error(), log | Next scheduled run |
| Single job parse error | try-catch per job, increment errors counter, continue | N/A (best-effort) |
| Company resolution failure | Create company with DISCOVERED status, proceed | N/A (never blocks) |
| AI extraction failure (BSJ) | Log, return empty for that page section | Next scheduled run |
| CLI subprocess timeout (Indeed) | Kill process, FetchResult.error() | Next scheduled run |

**Retry architecture**:
- No within-run retries for aggregators (expensive, rate-limited sources)
- Scheduling-based retry: if a source fails, it will be retried on next due cycle
- Circuit breaker pattern (existing for LinkedIn): after N consecutive failures, pause for cooldown
- Per-source `aggregator_run` table tracks `last_status` — admin can see health

**Rate limiting**:
- LinkedIn: Existing `LinkedInRateLimiter` injected into `McpStrategy`
- Indeed: Process-level (only one CLI subprocess at a time, `PROCESS_TIMEOUT = 60s`)
- BSJ: None needed (single HTML page, low frequency)
- Arbeitnow: Respect HTTP response headers, default 1 req/min

## Migration Path (Current → New)

### What Moves

| Current Location | New Location | Notes |
|-----------------|-------------|-------|
| `extraction/GreenhouseExtractor.java` | `strategy/ats/GreenhouseStrategy.java` | Rename + adapt interface |
| `extraction/LeverExtractor.java` | `strategy/ats/LeverStrategy.java` | Rename + adapt interface |
| `extraction/*Extractor.java` (×13) | `strategy/ats/*Strategy.java` | Mechanical rename |
| `extraction/GenericAiExtractor.java` | `strategy/direct/AiPageStrategy.java` | Rename + adapt interface |
| `extraction/ExtractionResult.java` | Deleted (replaced by `strategy/FetchResult.java`) | |
| `extraction/RawJobData.java` | Deleted (replaced by `strategy/RawAggregatorJob.java`) | |
| `extraction/JobExtractor.java` | Deleted (replaced by `strategy/FetchStrategy.java`) | |
| `extraction/JobExtractorRegistry.java` | Deleted (replaced by `ingestion/StrategyRegistry.java`) | |
| `linkedin/LinkedInJobSearchService.java` | Stays (thin shell delegating to AggregatorIngestionService) | Gutted to ~50 lines |
| `indeed/IndeedJobSearchService.java` | Stays (thin shell delegating to AggregatorIngestionService) | Gutted to ~50 lines |

### What Renames

| Item | Old Name | New Name |
|------|----------|----------|
| Interface | `JobExtractor` | `FetchStrategy` |
| Registry | `JobExtractorRegistry` | `StrategyRegistry` |
| Result type | `ExtractionResult` | `FetchResult` |
| Job data | `RawJobData` | `RawAggregatorJob` |
| Method | `extract(CareerEndpoint)` | `fetch(FetchContext)` |
| Method | `supportedTypes()` | `supports(AtsType)` |
| Entity field | `JobPosting.source: AtsType` | `JobPosting.source: JobSource` |

### What Gets Created (New)

| File | Package | Purpose |
|------|---------|---------|
| `FetchStrategy.java` | `strategy` | Universal fetch interface |
| `FetchContext.java` | `strategy` | Input record |
| `FetchResult.java` | `strategy` | Output record |
| `RawAggregatorJob.java` | `strategy` | Job data record |
| `StrategyRegistry.java` | `ingestion` | Routes type/name → strategy |
| `AggregatorIngestionService.java` | `ingestion` | Shared ingestion orchestration |
| `IngestionStats.java` | `ingestion` | Result record |
| `SourceConfig.java` | `source` | Aggregator source interface |
| `LinkedInSource.java` | `source` | LinkedIn config (dedicated, complex wiring) |
| `IndeedSource.java` | `source` | Indeed config (dedicated, complex wiring) |
| `DynamicSourceConfigLoader.java` | `source` | Reads `aggregator.sources[]` YAML, creates SourceConfig instances |
| `AggregatorSourceProperties.java` | `source` | `@ConfigurationProperties` for `aggregator.sources[]` |
| `YamlSourceConfig.java` | `source` | Record implementing SourceConfig for YAML-driven sources |
| `McpStrategy.java` | `strategy/aggregator` | LinkedIn fetch via MCP |
| `CliStrategy.java` | `strategy/aggregator` | Indeed fetch via jobspy-js |
| `AiAggregatorStrategy.java` | `strategy/aggregator` | BSJ fetch via HTML + AI |
| `RestApiStrategy.java` | `strategy/aggregator` | Arbeitnow fetch via REST |
| `JobSource.java` | `model/enums` | Universal source enum |
| `AggregatorRun.java` | `model` | JPA entity for run tracking |
| `AggregatorRunRepository.java` | `repository` | Spring Data repo |
| `005-job-source-migration.sql` | `db/changelog` | Schema migration |

### What Gets Deleted

| File | Reason |
|------|--------|
| `extraction/JobExtractor.java` | Replaced by `FetchStrategy` |
| `extraction/JobExtractorRegistry.java` | Replaced by `StrategyRegistry` |
| `extraction/ExtractionResult.java` | Replaced by `FetchResult` |
| `extraction/RawJobData.java` | Replaced by `RawAggregatorJob` |

### Migration Order (Phase-Aligned)

1. **Phase 1**: Create `JobSource`, `FetchStrategy`, `FetchContext`, `FetchResult`, `RawAggregatorJob`, `SourceConfig`, `StrategyRegistry`, `AggregatorIngestionService`. Run Liquibase migration. Update `JobPosting.source` type.
2. **Phase 2**: Move ATS extractors → strategies. Update CrawlService to use StrategyRegistry. Delete old extraction interfaces.
3. **Phase 3**: Create `McpStrategy`, `CliStrategy`, `LinkedInSource`, `IndeedSource`. Refactor LinkedIn/Indeed services to delegate.
4. **Phase 4**: Create `DynamicSourceConfigLoader`, `AggregatorSourceProperties`, `YamlSourceConfig`. Create `AiAggregatorStrategy`, `RestApiStrategy`. Configure BSJ + Arbeitnow as YAML entries. Remove fake BSJ company/endpoint.
5. **Phase 5**: Generalize filters — rename `germany-cities` → `target-cities`, add `filters.language.target` + `exclude-patterns`, remove hardcoded German/location patterns from LanguageFilter and GenericAiExtractor. Backward-compat mapping for old config keys.
6. **Phase 6**: Update PipelineScheduler to iterate SourceConfig beans. Add admin endpoints.

### Config Transition Guide (Existing → New)

This section documents how each existing config/code setting maps to the new YAML structure, ensuring no manual steps are missed during migration.

#### application.yaml Transition

| Current Path | New Path | Action | Notes |
|-------------|----------|--------|-------|
| `discovery.providers.linkedin.enabled` | `linkedin-mcp.enabled` (unchanged) | None | LinkedIn keeps dedicated source class |
| `discovery.providers.linkedin.keywords` | `linkedin-mcp.search.keywords` (unchanged) | None | Read by `LinkedInSource.buildContext()` |
| `discovery.providers.linkedin.locations` | `linkedin-mcp.search.locations` (unchanged) | None | Read by `LinkedInSource.buildContext()` |
| `discovery.providers.jobspy.enabled` | Unchanged | None | Indeed keeps dedicated source class |
| `discovery.providers.jobspy.keywords` | Unchanged | None | Read by `IndeedSource.buildContext()` |
| `linkedin-mcp.*` | Unchanged | None | MCP client config, rate limiter, circuit breaker |
| N/A (BSJ was a CareerEndpoint in DB) | `aggregator.sources[].{name: berlinstartupjobs, strategy: ai, ...}` | Add YAML entry | Replaces DB-stored endpoint + fake company |
| N/A (Arbeitnow had no config) | `aggregator.sources[].{name: arbeitnow, strategy: rest-api, ...}` | Add YAML entry | New source, previously only had AtsType enum |

#### profile.yaml Transition

| Current Key | New Key | Action | Backward Compat |
|-------------|---------|--------|-----------------|
| `filters.location.germany-cities` | `filters.location.target-cities` | Rename | Old key auto-maps to new with deprecation warning |
| N/A (hardcoded in LanguageFilter) | `filters.language.target: "en"` | Add | Defaults to `"en"` if absent |
| N/A (hardcoded German patterns) | `filters.language.exclude-patterns` | Add | Defaults to current German patterns for existing users |
| `filters.role.include-patterns` | Unchanged | None | Already generic |
| `filters.role.exclude-keywords` | Unchanged | None | Already generic |
| `filters.location.remote-patterns` | Unchanged | None | Already generic |
| `filters.yoe.max-years` | Unchanged | None | Already generic |

#### Database Transition

| Current State | New State | Migration Step |
|--------------|-----------|---------------|
| `job_posting.source = 'CUSTOM'` | `job_posting.source = 'DIRECT'` | Liquibase `005-job-source-migration.sql` |
| `job_posting.source = 'LINKEDIN'` | Unchanged (value same in new enum) | No migration needed |
| `job_posting.source = 'INDEED'` | Unchanged | No migration needed |
| BSJ jobs under fake company (`source = 'CUSTOM'`) | `source = 'BERLIN_STARTUP_JOBS'` | Liquibase migration updates based on company_id |
| `company` row "Berlin Startup Jobs" | Deleted | Liquibase or manual cleanup in Phase 4 |
| `career_endpoint` for BSJ (atsType=CUSTOM) | Deleted | Aggregators don't use endpoint model |
| N/A | New `aggregator_run` table | Liquibase creates table for scheduling state |

#### Code-Level Transition

| Current Code | New Code | What Happens |
|-------------|----------|--------------|
| `LinkedInJobSearchService.searchAndMatch()` (372 lines) | ~50 lines delegating to `AggregatorIngestionService.ingest(linkedInSource)` | Ingestion logic extracted to shared service |
| `IndeedJobSearchService.searchAndCreate()` (338 lines) | ~50 lines delegating to `AggregatorIngestionService.ingest(indeedSource)` | Same extraction |
| `CrawlService` uses `JobExtractorRegistry` | Uses `StrategyRegistry` | Drop-in replacement, same routing logic |
| `PipelineScheduler` directly calls LinkedIn/Indeed services | Iterates `List<SourceConfig>` beans | LinkedIn/Indeed become SourceConfig beans alongside YAML-driven ones |
| `GenericAiExtractor.looksLikeLocation()` hardcodes cities | `AiPageStrategy` reads `target-cities` from profile | Behavior unchanged if profile has same cities |
| `LanguageFilterImpl` hardcodes German patterns | Reads `language.exclude-patterns` from profile | Defaults preserve current behavior |

#### Rollback Path

If migration fails at any phase:
- **Phase 1 (DB)**: Liquibase rollback reverts column value changes. `JobSource` enum contains all old values, so rollback to `AtsType` is lossless.
- **Phase 2 (ATS move)**: Revert package moves. No runtime state dependency.
- **Phase 3 (LinkedIn/Indeed)**: Restore full service bodies. No DB impact.
- **Phase 4 (YAML sources)**: Remove `aggregator.sources[]` config, restore BSJ company/endpoint. DB jobs stay valid.
- **Phase 5 (Filters)**: Restore hardcoded patterns, revert profile.yaml key names. Backward-compat code handles both old and new keys.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Separate JobSource from AtsType | New enum, AtsType retained for CareerEndpoint | AtsType semantics = "ATS platform". JobSource = "origin of a job". Aggregators are not ATS. | Extend AtsType with more values | Cleaner separation; migration required |
| companyName on job record | null = from endpoint, non-null = resolve | Single type covers all strategies without subclassing | Separate ATS vs Aggregator result types | Null-check needed but simpler type hierarchy |
| StrategyRegistry auto-discovery | Constructor injects `List<FetchStrategy>` | Same pattern as existing `JobExtractorRegistry`, zero config | Manual registration map | Spring handles ordering; can't easily prioritize |
| Keep LinkedIn/Indeed packages as thin shells | Delegate to AggregatorIngestionService | Backward compat for existing tests and `@ConditionalOnProperty` | Delete packages entirely | Extra indirection; safer migration |
| aggregator_run table for scheduling | DB-persisted last run time | Survives restarts, admin-visible | In-memory tracking | DB write on each run; negligible overhead |
| No within-run retries | Schedule-based retry only | Aggregators are rate-limited; immediate retry burns quota | Exponential backoff | Simpler; longer delay between attempts |
| YAML-driven sources vs class-per-source | `aggregator.sources[]` list with `DynamicSourceConfigLoader` | Zero Java for new simple sources. Config-only onboarding for new users/regions. | One `@Component` class per source (BsjSource.java, ArbeitnowSource.java) | Enum values still need Java code; complex sources (LinkedIn, Indeed) still need dedicated classes; but simple sources are pure config |
| Generalized filters over hardcoded locale | Config-driven patterns (`target-cities`, `language.target`, `exclude-patterns`) | New user in any country configures via YAML. No code changes for different locale. | Keep German-specific code, add locale switch | Migration effort; existing behavior preserved via default config values |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| JobSource migration breaks queries | Repository methods referencing AtsType stop compiling | High (compile error, caught immediately) | Phase 1 updates all repository signatures. Mechanical find-replace. |
| Rename 13 extractors causes merge conflicts | Other PRs touching extractors conflict | Medium | Do Phase 2 in single commit. Communicate to avoid parallel work. |
| LinkedIn rate limiter not integrated into McpStrategy | Burns rate limit quota, gets IP blocked | Medium | McpStrategy constructor-injects existing LinkedInRateLimiter. Integration test verifies. |
| BSJ AI prompt fails to extract company name | Jobs saved under "Unknown" company, no value | Medium | Fallback to page-level heuristic (DOM structure). Log extraction confidence. Admin health report surfaces issues. |
| Indeed CLI subprocess hangs | Blocks pipeline thread indefinitely | Low | Existing PROCESS_TIMEOUT (60s) applies. CliStrategy wraps with CompletableFuture.orTimeout(). |
| AggregatorIngestionService filter cascade order | Wrong order causes valid jobs to be filtered | Low | Same order as existing LinkedIn/Indeed code. Unit test each cascade step. |
| Language filter generalization regression | Removing hardcoded German patterns may miss non-English jobs that were previously caught | Medium | Existing German patterns become DEFAULT values in `filters.language.exclude-patterns`. Behavior unchanged unless user modifies config. Unit tests verify backward-compat with default config. |
| Dynamic enum limitation for YAML sources | YAML-defined sources still require `JobSource` + `DiscoverySource` enum values in Java code | Medium | Accept enum limitation. Document that adding a new source requires adding enum value. Most users won't add more than a few sources. Future: consider String-based storage if enum churn becomes a problem. |
| DynamicSourceConfigLoader startup failure | Invalid YAML (bad strategy name, missing enum value) crashes application startup | Low | Validate at startup with clear error messages. `@PostConstruct` validates all entries resolve. Log successful source registrations. |

## Filter Generalization

### Goal

Remove all hardcoded country/language/location assumptions. A new user in any region configures the system entirely via `profile.yaml` — no code changes needed.

### profile.yaml Config Changes

| Old Key | New Key | Reason |
|---------|---------|--------|
| `filters.location.germany-cities` | `filters.location.target-cities` | Locale-neutral naming |
| _(new)_ | `filters.language.target` | Target language (e.g. "en", "de") |
| _(new)_ | `filters.language.exclude-patterns` | Regexes for "requires $LANGUAGE" detection |

**Full filter config after generalization**:

```yaml
filters:
  role:
    include-patterns: ["engineer", "developer", "architect"]   # unchanged
    exclude-keywords: ["manager", "director", "devops"]        # unchanged
  location:
    target-cities: ["berlin", "munich", "hamburg", "remote"]   # renamed from germany-cities
    remote-patterns: ["remote", "remote.*germany"]             # unchanged
  language:
    target: "en"                                                # NEW: target language ISO code
    exclude-patterns:                                           # NEW: regexes that indicate non-target language requirement
      - "deutsch C[12]"
      - "fließend deutsch"
      - "muttersprache"
      - "german (native|fluent|required)"
  yoe:
    max-years: 8                                                # unchanged
```

### LanguageFilter Changes

| Before | After |
|--------|-------|
| Hardcoded German patterns in Java | Patterns read from `filters.language.exclude-patterns` at construction |
| Hardcoded target language assumption | Target read from `filters.language.target` |
| Recompile to change detection rules | Edit profile.yaml, restart |

```java
// Before (hardcoded):
private static final List<Pattern> GERMAN_PATTERNS = List.of(
    Pattern.compile("deutsch C[12]", CASE_INSENSITIVE),
    Pattern.compile("fließend deutsch", CASE_INSENSITIVE),
    // ...
);

// After (config-driven):
public LanguageFilter(PersonalProfileLoader profileLoader) {
    var config = profileLoader.getProfile().getFilters().getLanguage();
    this.targetLanguage = config.getTarget();  // "en"
    this.excludePatterns = config.getExcludePatterns().stream()
        .map(p -> Pattern.compile(p, CASE_INSENSITIVE))
        .toList();
}
```

### GenericAiExtractor / AiPageStrategy Changes

| Before | After |
|--------|-------|
| `looksLikeLocation()` checks hardcoded "berlin", "munich", "hamburg" | Reads `filters.location.target-cities` from profile |
| AI prompt assumes German job market context | Locale-neutral prompt: "Extract job title, location, company" (no country assumptions) |

### Backward Compatibility

Old config keys continue to work during transition:

```java
// In PersonalProfileLoader or config binding:
// If "germany-cities" key exists and "target-cities" does not → map old to new
if (filters.getLocation().getTargetCities() == null
    && filters.getLocation().getGermanyCities() != null) {
    filters.getLocation().setTargetCities(filters.getLocation().getGermanyCities());
    log.warn("Deprecated: 'filters.location.germany-cities' → use 'filters.location.target-cities'");
}
```

### Default Values

Existing users who don't update their profile.yaml get the same behavior:
- `target-cities` defaults to value of `germany-cities` if present
- `language.target` defaults to `"en"`
- `language.exclude-patterns` defaults to the current hardcoded German patterns

## User Onboarding

A new user configures the entire system via two files. No code changes needed for different country/role/domain.

### Example: US User Targeting Remote Python Roles

**profile.yaml**:
```yaml
name: "Alex"
skills:
  - {name: "Python", proficiency: "expert", category: "language"}
  - {name: "FastAPI", proficiency: "advanced", category: "framework"}
  - {name: "AWS", proficiency: "intermediate", category: "cloud"}
preferences:
  locations: ["San Francisco", "Remote US"]
  seniority: "senior"
filters:
  role:
    include-patterns: ["engineer", "developer", "SRE", "platform"]
    exclude-keywords: ["manager", "director", "sales", "marketing"]
  location:
    target-cities: ["san francisco", "new york", "seattle", "austin"]
    remote-patterns: ["remote", "remote.*us", "remote.*united states"]
  language:
    target: "en"
    exclude-patterns: []   # English-only market, no exclusion needed
  yoe:
    max-years: 10
scoring:
  primary-skills: ["python", "fastapi"]
  skill-variants:
    python: ["python", "python3", "py"]
    fastapi: ["fastapi", "fast-api"]
    aws: ["aws", "amazon web services", "ec2", "lambda"]
```

**application.yaml** (aggregator sources):
```yaml
aggregator:
  sources:
    - name: weworkremotely
      strategy: rest-api
      job-source: WEWORKREMOTELY
      discovery-source: WEWORKREMOTELY
      url: "https://weworkremotely.com/api/..."
      frequency-hours: 6
      max-results: 50
    - name: hn-whoishiring
      strategy: ai
      job-source: HN_WHO_IS_HIRING
      discovery-source: HN_WHO_IS_HIRING
      url: "https://news.ycombinator.com/item?id=..."
      frequency-hours: 24
      max-results: 100
```

### Example: EU User Targeting Berlin-Based Java Roles

**profile.yaml**:
```yaml
name: "Max"
skills:
  - {name: "Java", proficiency: "expert", category: "language"}
  - {name: "Spring Boot", proficiency: "expert", category: "framework"}
  - {name: "Kotlin", proficiency: "advanced", category: "language"}
preferences:
  locations: ["Berlin", "Remote"]
  seniority: "senior"
filters:
  role:
    include-patterns: ["engineer", "developer", "architect"]
    exclude-keywords: ["manager", "devops", "mlops", "data engineer"]
  location:
    target-cities: ["berlin", "potsdam", "remote"]
    remote-patterns: ["remote", "remote.*germany", "remote.*europe"]
  language:
    target: "en"
    exclude-patterns:
      - "deutsch C[12]"
      - "fließend deutsch"
      - "muttersprache"
      - "german (native|fluent|required)"
  yoe:
    max-years: 8
scoring:
  primary-skills: ["java", "spring boot", "kotlin"]
  skill-variants:
    java: ["java", "java 17", "java 21"]
    spring-boot: ["spring boot", "spring-boot", "springboot"]
    kotlin: ["kotlin", "kt"]
```

**application.yaml** (aggregator sources):
```yaml
aggregator:
  sources:
    - name: berlinstartupjobs
      strategy: ai
      job-source: BERLIN_STARTUP_JOBS
      discovery-source: BERLIN_STARTUP_JOBS
      url: "https://berlinstartupjobs.com/engineering/"
      frequency-hours: 12
      max-results: 30
    - name: arbeitnow
      strategy: rest-api
      job-source: ARBEITNOW
      discovery-source: ARBEITNOW
      url: "https://www.arbeitnow.com/api/job-board-api"
      frequency-hours: 6
      max-results: 50
```

### What Requires Code Changes

| Action | Code needed? |
|--------|-------------|
| New user, different country | No — profile.yaml + application.yaml only |
| New aggregator source, existing strategy type | No — YAML entry only |
| New aggregator source, new transport type | Yes — 1 FetchStrategy class |
| New enum values for source tracking | Yes — add to `JobSource` + `DiscoverySource` enums |
| New filter type (e.g., salary filter) | Yes — implement filter interface |

## Test Plan

### Unit Tests

**FetchStrategy implementations (per strategy)**:
- Happy path: mock transport returns valid response → FetchResult.SUCCESS with correct jobs
- Empty response: transport returns no jobs → FetchResult.EMPTY
- Error response: transport throws/returns error → FetchResult.ERROR with message
- Rate limited: transport returns 429 → FetchResult.RATE_LIMITED
- Pagination: verify maxPages respected (mock returns paginated data)
- LinkedIn McpStrategy: verify rate limiter is checked before each call
- Indeed CliStrategy: verify subprocess timeout handling
- BSJ AiAggregatorStrategy: verify company name extraction from AI response
- Arbeitnow RestApiStrategy: verify JSON parsing from API response

**AggregatorIngestionService**:
- Given FetchResult with 5 jobs, 2 existing by externalId → 3 processed, 2 skipped
- Given 1 job with fingerprint matching ATS job → enriched (external link added)
- Given 1 job failing role filter → filtered count = 1, created = 0
- Given 1 job with unknown company → new Company created with DISCOVERED status
- Given FetchResult.error → IngestionStats with 0 processed, error logged
- Given FetchResult.rateLimited → IngestionStats empty, source marked not-due
- Full cascade: language → role → location → yoe → dedup (verify short-circuit)

**StrategyRegistry**:
- Given 3 strategies registered → getStrategy(GREENHOUSE) returns correct one
- Given unknown AtsType → getStrategy returns Optional.empty
- Given strategy with name "linkedin-mcp" → getStrategy("linkedin-mcp") resolves

**SourceConfig implementations**:
- Each source builds correct FetchContext from config properties
- `frequencyHours()` reads from application.yaml
- `isEnabled()` reflects conditional property

**DynamicSourceConfigLoader**:
- Given valid `aggregator.sources[]` YAML with 2 entries → creates 2 SourceConfig beans
- Given entry with invalid strategy name → throws `IllegalStateException` at startup with clear message
- Given entry with invalid `job-source` enum value → throws at startup
- Given empty `aggregator.sources[]` list → returns empty list (no error)
- Each created SourceConfig: `strategy()` returns correct FetchStrategy, `buildContext()` includes URL from config
- Sources are registered alongside dedicated sources (LinkedInSource, IndeedSource)

**Filter generalization (LanguageFilter)**:
- Given `language.target: "en"` + exclude-patterns containing "deutsch C[12]" → rejects job with "deutsch C1 erforderlich"
- Given empty exclude-patterns → accepts all jobs (no language filtering)
- Given custom patterns for Spanish market → rejects "requiere inglés nativo" if configured
- Backward compat: old `germany-cities` key maps to `target-cities` with deprecation warning

**Filter generalization (LocationFilter)**:
- Given `target-cities: ["san francisco", "new york"]` → accepts "San Francisco, CA", rejects "London, UK"
- Given `remote-patterns: ["remote.*us"]` → accepts "Remote (US)", rejects "Remote (EU)"

**Mocking**: WireMock for HTTP strategies, mock ProcessBuilder for CLI, mock HttpMcpClient for MCP.

### Integration Tests

**End-to-end aggregator ingestion** (Testcontainers + PostgreSQL):
- Seed DB with 2 companies + 3 ATS job postings
- Run AggregatorIngestionService with mock FetchResult containing: 1 job matching existing (dedup), 1 new job for existing company, 1 new job for unknown company
- Assert: 1 enriched, 1 created under existing company, 1 created under new company
- Assert: JobPosting.source = correct JobSource enum value
- Assert: aggregator_run table updated with stats

**Cross-source deduplication**:
- Create job via ATS crawl (source=GREENHOUSE, fingerprint=X)
- Run aggregator ingestion with job producing same fingerprint
- Assert: existing job enriched with external link, no duplicate created

**Migration verification**:
- Run Liquibase migration on test DB with existing data
- Assert: `CUSTOM` → `DIRECT` mapping correct
- Assert: BSJ company jobs → `BERLIN_STARTUP_JOBS`
- Assert: all other source values unchanged

### End-to-End Tests

**Full pipeline run**:
- PipelineScheduler.runPipeline() with real StrategyRegistry
- 1 ATS endpoint (WireMock), 1 aggregator source (WireMock)
- Assert: both tracks complete, scoring triggered, stats logged

**Admin API**:
- `POST /api/admin/aggregate/linkedin` → triggers single source ingestion
- `GET /api/admin/aggregators` → returns list with last run stats

### Non-Functional Tests

**Performance**:
- AggregatorIngestionService processes 100 jobs in < 5s (excluding transport time)
- StrategyRegistry lookup is O(1) hash map access

**Resilience**:
- Strategy timeout does not block other sources (parallel execution)
- Single job processing error does not abort entire source ingestion
- Pipeline completes even if one source fails entirely
