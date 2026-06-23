# HLD: BuiltInEurope Aggregator Source

## Tech Stack

| Category  | Technology         | Purpose                                      |
| --------- | ------------------ | -------------------------------------------- |
| Language  | Java 21            | Matches existing API codebase                |
| Framework | Spring Boot 3.3.5  | DI, component scan, WebClient                |
| HTTP      | WebClient          | Non-blocking POST to BuiltInEurope search API |
| JSON      | Jackson ObjectMapper | Parse JSON responses                        |
| Config    | Spring Boot YAML   | Source config via `AggregatorSourceProperties` |
| Testing   | JUnit 5 + WireMock | Mock HTTP, verify pagination/dedup logic     |

## Components

| Component                | Responsibility                                          | Dependencies                              |
| ------------------------ | ------------------------------------------------------- | ----------------------------------------- |
| `BuiltInEuropeStrategy`  | Fetch jobs via POST API, paginate, dedup across keywords | WebClient, ObjectMapper                   |
| `YamlSourceConfig` (mod) | Parse `queries` config key into `FetchContext.keywords()` | FetchContext                              |
| `JobSource` (mod)        | New `BUILTIN_EUROPE` enum value                         | None                                      |
| `DiscoverySource` (mod)  | New `BUILTIN_EUROPE` enum value                         | None                                      |
| `application.yaml` (mod) | Source entry with strategy, queries, maxResults         | AggregatorSourceProperties                |

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                       PipelineScheduler                           │
│   [iterates dynamicSources, includes builtineurope]              │
└─────────────────────────────┬────────────────────────────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  AggregatorIngestionService   │
               │  ingest(builtineuropeSource)  │
               └──────────────┬───────────────┘
                              │ source.strategy().fetch(context)
                              ▼
               ┌──────────────────────────────────────────────┐
               │         BuiltInEuropeStrategy                 │
               │  (implements FetchStrategy, name="builtineurope") │
               └──────┬──────────────────────────┬────────────┘
                      │                          │
           For each keyword                Dedup by id
           in context.keywords()           across all keywords
                      │                          │
                      ▼                          │
        ┌───────────────────────────┐            │
        │  POST /search             │            │
        │  {"query":"engineer",     │            │
        │   "page":1,"per_page":100}│            │
        │                           │            │
        │  Paginate until:          │            │
        │  - empty results          │            │
        │  - maxResults hit         │            │
        └─────────────┬─────────────┘            │
                      │ List<JsonNode>            │
                      └──────────────────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  Map → RawAggregatorJob       │
               │  FetchResult.success(jobs)    │
               └──────────────┬───────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │  Existing Filter Chain        │
               │  Language → Role → Location   │
               │  → YOE → Visa → Dedup        │
               └──────────────────────────────┘
```

**Description:** `BuiltInEuropeStrategy` is a `@Component` implementing `FetchStrategy`. It receives a `FetchContext` with `keywords()` populated from YAML `config.queries`. For each keyword, it POSTs to the BuiltInEurope search API, paginates through results, collects raw job nodes. After all keywords are exhausted, it deduplicates by `id` field, maps to `RawAggregatorJob`, and returns a `FetchResult`. The existing pipeline (filter chain, scoring, persistence) handles everything downstream.

The `YamlSourceConfig.buildContext()` enhancement is a shared abstraction: any YAML-configured source with `config.queries` will get `FetchContext.keywords()` populated automatically.

## Interfaces

### FetchStrategy (existing - implemented by BuiltInEuropeStrategy)

| Method     | Input          | Output       | Behavior                                           | Errors                   |
| ---------- | -------------- | ------------ | -------------------------------------------------- | ------------------------ |
| `name()`   | none           | `String`     | Returns `"builtineurope"`                          | None                     |
| `supports(AtsType)` | AtsType | `boolean` | Returns `false` (aggregator-only)                  | None                     |
| `fetch(FetchContext)` | FetchContext | `FetchResult` | Fan-out keywords, paginate, dedup, map to jobs | `FetchResult.error(msg)` |

### BuiltInEuropeStrategy (internal methods)

| Method            | Input                        | Output                  | Behavior                                                     | Errors                  |
| ----------------- | ---------------------------- | ----------------------- | ------------------------------------------------------------ | ----------------------- |
| `fetchKeyword`    | `String keyword, int maxResults, String apiUrl` | `List<JsonNode>`       | POST paginated requests for one keyword until empty/limit     | Throws on HTTP error    |
| `mapJob`          | `JsonNode`                   | `RawAggregatorJob`     | Extract fields from single result node                       | Returns null if no title |
| `buildRequestBody`| `String query, int page, int perPage` | `String` (JSON)  | Construct POST body                                          | None                    |

### YamlSourceConfig.buildContext() (modified)

| Method         | Input  | Output        | Behavior                                                                                   | Errors |
| -------------- | ------ | ------------- | ------------------------------------------------------------------------------------------ | ------ |
| `buildContext` | none   | `FetchContext` | If `extraConfig` has key `"queries"` → split on `,`, trim → pass as `keywords` to `forSearch()` | None   |

## Data Flow

| Step | Component                  | Action                                                        | Next                       |
| ---- | -------------------------- | ------------------------------------------------------------- | -------------------------- |
| 1    | PipelineScheduler          | Iterates `dynamicSources`, picks builtineurope                | AggregatorIngestionService |
| 2    | AggregatorIngestionService | Calls `source.buildContext()` → `FetchContext` with keywords  | BuiltInEuropeStrategy      |
| 3    | BuiltInEuropeStrategy      | For each keyword: POST `api.builtineurope.com/search` page=1  | Self (pagination loop)     |
| 4    | BuiltInEuropeStrategy      | Increment page until results empty or maxResults reached      | Self (next keyword)        |
| 5    | BuiltInEuropeStrategy      | Deduplicate all collected nodes by `id` (LinkedHashSet)       | Mapping                    |
| 6    | BuiltInEuropeStrategy      | Map each node → `RawAggregatorJob`, skip null titles          | FetchResult                |
| 7    | BuiltInEuropeStrategy      | Return `FetchResult.success(jobs, elapsed)`                   | AggregatorIngestionService |
| 8    | Filter Chain               | Language/Role/Location/YOE/Dedup filters applied              | Persistence                |
| 9    | Persistence                | Upsert jobs into `job_posting` table                          | Done                       |

**Error Flows:**
- HTTP 4xx/5xx from API → log error, return `FetchResult.error(message, elapsed)`. Pipeline skips this source for current cycle.
- Timeout (30s per request) → WebClient throws `WebClientResponseException`, caught → `FetchResult.error(...)`.
- Rate limit (429) → `RetryableWebClientFilter` retries 3x with backoff. If exhausted → `FetchResult.rateLimited(elapsed)`.
- Malformed JSON response → `ObjectMapper` throws, caught → `FetchResult.error(...)`.
- Single keyword fails but others succeed → partial results returned (best-effort).

## Data Model

No new entities. Mapping from API response to existing `RawAggregatorJob` record:

### Field Mapping: API Response → RawAggregatorJob

| API Field                  | RawAggregatorJob Field | Transform                                                    |
| -------------------------- | ---------------------- | ------------------------------------------------------------ |
| `id`                       | `externalId`           | Direct string                                                |
| `title_raw`               | `title`                | Direct string                                                |
| `company_display_name`    | `companyName`          | Direct string                                                |
| `location_name`           | `location`             | Direct string (e.g. "Berlin, Germany")                       |
| `posting_url`             | `applyUrl`             | Direct string (ATS URL - Greenhouse/Ashby/etc)               |
| `skills[]`                | `description`          | Join with ", " as supplemental description                   |
| `first_seen`              | `postedDate`           | Epoch seconds → `Instant.ofEpochSecond()` → `LocalDate` (UTC) |
| `salary_min`              | `salaryMin`            | `BigDecimal` (null if absent)                                |
| `salary_max`              | `salaryMax`            | `BigDecimal` (null if absent)                                |
| `salary_currency`         | `salaryCurrency`       | Direct string (e.g. "EUR")                                   |
| Full JSON node            | `rawJson`              | `node.toString()`                                            |

### Unused API fields (available for future use)
- `location.country`, `location.continent` — redundant with `location_name`
- `remote_work_policy` — could enrich location filter later
- `seniority` — could feed YOE filter later
- `salary_period` — assumed annual, not stored

## Config Schema

### YAML entry in `application.yaml` under `aggregator.sources`

```yaml
- name: builtineurope
  strategy: builtineurope
  job-source: BUILTIN_EUROPE
  discovery-source: BUILTIN_EUROPE
  url: "https://api.builtineurope.com/search"
  frequency-hours: 6
  max-results: 500
  config:
    queries: "engineer,developer"
```

### Config key semantics

| Key              | Type   | Required | Default    | Description                                            |
| ---------------- | ------ | -------- | ---------- | ------------------------------------------------------ |
| `queries`        | String | No       | `""`       | Comma-separated keywords. Each triggers separate search |
| `url`            | String | Yes      | —          | API endpoint (injected by DynamicSourceConfigLoader)   |
| `max-results`    | int    | No       | 50         | Total job cap across all keywords                      |
| `frequency-hours`| int    | No       | 12         | Informational only (pipeline runs all sources per tick) |

## Decisions

| Decision                            | Choice                                      | Reason                                                           | Alternatives                                      | Tradeoffs                                                          |
| ----------------------------------- | ------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------- | ------------------------------------------------------------------ |
| Fan-out per keyword                 | Iterate `context.keywords()`, dedup by `id` | API treats multi-word queries as AND; boolean OR unsupported      | Single query with broadest term                   | More HTTP calls but better coverage; dedup handles overlap         |
| Dedup structure                     | `LinkedHashMap<String, JsonNode>` by `id`   | Preserves insertion order, O(1) lookup                           | HashSet + post-sort                               | Minimal memory overhead for ~500 results                           |
| `queries` abstraction in YamlSourceConfig | Parse in `buildContext()` from `extraConfig` | All YAML sources benefit; no per-strategy config parsing        | Each strategy parses own config                   | Slight coupling to config key name; worth the DRY benefit          |
| POST body construction              | Manual JSON string builder                  | Simple 3-field body; no need for DTO class or Jackson serialization | Create a request DTO + ObjectMapper.writeValueAsString | Less code, matches MeilisearchStrategy pattern                     |
| Pagination termination              | Empty results OR maxResults reached         | API may not return exact page count; defensive stop              | Use `total_jobs` from response to calculate pages | total_jobs could be stale; empty-page check is more reliable       |
| `skills[]` as description           | Join skills into description string         | No dedicated skills field in RawAggregatorJob; helps scoring     | Ignore skills entirely                            | Scorer uses description for keyword matching; skills feed directly  |

## Risks

| Risk                                     | Impact                                     | Likelihood | Mitigation                                                  |
| ---------------------------------------- | ------------------------------------------ | ---------- | ----------------------------------------------------------- |
| API adds authentication                  | Source stops working                       | Low        | Monitor for 401/403; alert via health endpoint              |
| API rate limits aggressive requests      | Partial/no results                         | Medium     | Per-page delay (configurable), RetryableWebClientFilter handles 429 |
| API schema changes field names           | Null fields, jobs skipped                  | Low        | rawJson preserved for debugging; log warnings on null titles |
| Duplicate jobs with existing ATS crawls  | Same job from both direct crawl + aggregator | High      | Existing dedup pipeline handles by `externalId`/URL matching |
| Non-EU jobs in results                   | Irrelevant jobs ingested                   | High       | Existing LocationFilter discards non-target locations        |
| Large result set causes memory pressure  | OOM on very broad queries                  | Low        | `maxResults: 500` cap; pagination stops at limit            |
| `first_seen` epoch = discovery date, not post date | Slightly stale postedDate        | Medium     | Acceptable; better than null date                           |

## Test Plan

### Unit Tests

#### `BuiltInEuropeStrategyTest`

| Scenario                         | Setup                                                              | Assertion                                                     |
| -------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------- |
| Happy path - single keyword      | WireMock returns 2 pages, 3 jobs total                            | FetchResult.success with 3 jobs, correct field mapping        |
| Multi-keyword dedup              | 2 keywords return overlapping jobs (shared `id`)                  | Deduped list contains each job once                           |
| Pagination stops on empty page   | Page 1 returns 100 jobs, page 2 returns 0                        | Only page 1 jobs returned                                    |
| maxResults cap                   | API has 1000 jobs, maxResults=50                                  | Returns exactly 50 jobs, no further pages fetched             |
| Empty keywords list              | `context.keywords()` is empty                                     | Single request with `query: ""`, returns all available        |
| API error (500)                  | WireMock returns 500                                              | `FetchResult.error(...)` with message                        |
| Malformed JSON                   | WireMock returns invalid JSON                                     | `FetchResult.error(...)` with parse error                    |
| Null title in result             | One job node missing `title_raw`                                  | Job skipped, others still returned                           |
| Salary mapping                   | Result has `salary_min`, `salary_max`, `salary_currency`          | BigDecimal values correctly mapped                           |
| `first_seen` epoch conversion    | `first_seen: 1719100800` (epoch seconds)                         | `postedDate = 2024-06-23`                                    |
| Skills as description            | `skills: ["Java", "Spring Boot"]`                                 | `description = "Java, Spring Boot"`                          |

#### `YamlSourceConfigTest` (queries parsing)

| Scenario                     | Input                                  | Assertion                                |
| ---------------------------- | -------------------------------------- | ---------------------------------------- |
| Queries present              | `extraConfig: {queries: "a,b,c"}`      | `context.keywords() == ["a", "b", "c"]`  |
| Queries with whitespace      | `extraConfig: {queries: " a , b "}`    | `context.keywords() == ["a", "b"]`       |
| Queries absent               | `extraConfig: {}`                      | `context.keywords() == []`               |
| Queries empty string         | `extraConfig: {queries: ""}`           | `context.keywords() == []`               |

### Integration Tests

Not required. No schema changes, no new Spring beans beyond component-scanned strategy. Existing `DynamicSourceConfigLoaderTest` pattern validates wiring.

### End-to-End Verification

- After deploy: `curl -X POST localhost:8080/api/admin/crawl` triggers pipeline
- Verify via: `SELECT count(*) FROM job_posting WHERE source = 'BUILTIN_EUROPE'`
- Health endpoint shows builtineurope source status

### Non-Functional

| Concern     | Requirement                                                        |
| ----------- | ------------------------------------------------------------------ |
| Performance | < 30s total fetch time for 500 jobs (5 pages × 2 keywords)        |
| Resilience  | Single keyword failure doesn't abort entire fetch                  |
| Memory      | ~500 jobs × ~2KB JSON ≈ 1MB peak; no concern                      |
| Idempotency | Re-running pipeline produces same results (dedup by externalId)    |

## File Change List

| File                                                                          | Change Type | Description                                      |
| ----------------------------------------------------------------------------- | ----------- | ------------------------------------------------ |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/BuiltInEuropeStrategy.java` | **New**     | FetchStrategy implementation (~120 lines)        |
| `api/src/main/java/dev/jobhunter/source/YamlSourceConfig.java`               | Modify      | Parse `queries` in `buildContext()`              |
| `api/src/main/java/dev/jobhunter/model/enums/JobSource.java`                 | Modify      | Add `BUILTIN_EUROPE` to enum + AGGREGATORS list  |
| `api/src/main/java/dev/jobhunter/model/enums/DiscoverySource.java`           | Modify      | Add `BUILTIN_EUROPE` to enum                     |
| `api/src/main/resources/application.yaml`                                     | Modify      | Add source entry under `aggregator.sources`      |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/BuiltInEuropeStrategyTest.java` | **New** | Unit tests with WireMock (~150 lines)            |
| `api/src/test/java/dev/jobhunter/source/YamlSourceConfigTest.java`           | Modify/New  | Tests for queries parsing                        |
