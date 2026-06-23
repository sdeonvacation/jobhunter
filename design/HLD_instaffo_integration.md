# HLD: Instaffo Integration

## 1. Overview

Integrate Instaffo (jobs.instaffo.com) as a new aggregator source. Instaffo is a German tech job platform with ~1000+ listings. No public API exists — integration uses sitemap XML parsing for discovery + Jsoup HTML scraping of individual job pages. Structured data (JSON-LD `JobPosting` schema) embedded in each page provides reliable field extraction. Salary data is extracted from rendered HTML or RSC flight data.

**Goals:**
- Discover job URLs from sitemap XML (`sitemap-jobs.xml`)
- Scrape job details from individual pages using JSON-LD structured data
- Extract salary when available (from visible HTML)
- Integrate via existing `FetchStrategy` contract — no pipeline changes required
- Respect rate limits with configurable delays between fetches
- Support incremental fetching (skip already-seen jobs)

## 2. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         PipelineScheduler                                 │
│  [iterates allSources, picks up instaffo via DynamicSourceConfigLoader]  │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │
                                       ▼
                         ┌──────────────────────────┐
                         │ AggregatorIngestionService │
                         │  ingest(instaffoSource)    │
                         └─────────────┬──────────────┘
                                       │ calls source.strategy().fetch(context)
                                       ▼
                         ┌──────────────────────────────┐
                         │      InstaffoStrategy         │
                         │  (implements FetchStrategy)   │
                         └──────┬─────────────┬─────────┘
                                │             │
                    Phase 1     │             │  Phase 2
                    (discover)  │             │  (scrape details)
                                ▼             ▼
               ┌─────────────────┐   ┌──────────────────────┐
               │  SitemapParser   │   │  JobDetailScraper    │
               │                 │   │                      │
               │ GET sitemap XML │   │ GET /en/job/{slug}   │
               │ Parse <loc> URLs│   │ Parse JSON-LD        │
               │ Filter /en/job/ │   │ Extract salary from  │
               │ Extract UUIDs   │   │   visible HTML       │
               └────────┬────────┘   └──────────┬───────────┘
                        │                        │
                        │  List<String> urls      │  RawAggregatorJob
                        └────────────────────────┘
                                       │
                                       ▼
                         ┌──────────────────────────┐
                         │   FetchResult.success()    │
                         │   List<RawAggregatorJob>   │
                         └──────────────────────────┘
                                       │
                                       ▼
                         ┌──────────────────────────────┐
                         │  Existing Filter Chain        │
                         │  Language → Role → Location   │
                         │  → YOE → Visa → Dedup        │
                         └──────────────────────────────┘
                                       │
                                       ▼
                         ┌──────────────────────────┐
                         │  JobPostingRepository     │
                         └──────────────────────────┘
```

**External Dependencies:**
- `https://jobs.instaffo.com/sitemap-jobs.xml` — job URL discovery
- `https://jobs.instaffo.com/en/job/{slug}` — individual job pages (SSR HTML)

## 3. Components

### 3.1 Abstract Base: `SitemapScrapeStrategy`

**Package:** `dev.jobhunter.strategy.aggregator`

**Responsibility:** Generic two-phase strategy (sitemap discovery → detail scraping) reusable across any sitemap-based job source. Concrete subclasses only provide source-specific configuration and page parsing logic.

```java
@Slf4j
public abstract class SitemapScrapeStrategy implements FetchStrategy {

    private final WebClient webClient;
    private final JobPostingRepository jobPostingRepository;

    // --- Extension points (override in subclass) ---

    /** Strategy name used by StrategyRegistry and YAML config */
    @Override
    public abstract String name();

    /** Pattern to filter sitemap <loc> URLs (e.g. "^.*/en/job/.+$") */
    protected abstract Pattern urlFilterPattern();

    /** Extract externalId from a matched URL (e.g. last 12 hex chars) */
    protected abstract String extractExternalId(String url);

    /** Parse a single detail page HTML into a RawAggregatorJob */
    protected abstract Optional<RawAggregatorJob> parsePage(String html, String url, String externalId);

    /** JobSource enum value for incremental skip queries */
    protected abstract JobSource jobSource();

    // --- Configurable defaults (overridable) ---

    /** Delay between page fetches (ms). Override or set via config map. */
    protected int defaultDelayMs() { return 400; }

    /** Max pages to scrape per run. Override or set via config map. */
    protected int defaultMaxScrapePerRun() { return 50; }

    /** Sitemap timeout (seconds) */
    protected int sitemapTimeoutSeconds() { return 30; }

    /** Detail page timeout (seconds) */
    protected int pageTimeoutSeconds() { return 15; }

    // --- Core logic (final, not overridable) ---

    @Override
    public final boolean supports(AtsType type) { return false; }

    @Override
    public final FetchResult fetch(FetchContext context) {
        // 1. Fetch + parse sitemap
        // 2. Filter URLs via urlFilterPattern()
        // 3. Extract externalIds via extractExternalId()
        // 4. Query DB for known IDs (incremental skip)
        // 5. Scrape new pages sequentially with delay
        // 6. Call parsePage() for each, collect results
        // 7. Stop on 429 or maxScrapePerRun reached
        // 8. Return FetchResult.success(jobs) or error
    }
}
```

**Key behaviors (inherited by all subclasses):**
1. Fetch sitemap XML via WebClient (URL from `FetchContext`)
2. SAX-stream parse `<loc>` entries, filter via `urlFilterPattern()`
3. Extract externalIds, deduplicate against DB
4. Scrape only new pages, respect `delayBetweenMs` and `maxScrapePerRun`
5. Handle 404/410 (skip), 429 (stop early, return partial), 5xx (skip, log)
6. Return `FetchResult.success(partialJobs)` even on partial completion

**Config keys read from `FetchContext.config()` map:**
- `delayBetweenMs` → overrides `defaultDelayMs()`
- `maxScrapePerRun` → overrides `defaultMaxScrapePerRun()`

### 3.2 Concrete: `InstaffoStrategy extends SitemapScrapeStrategy`

**Package:** `dev.jobhunter.strategy.aggregator`

**Responsibility:** Instaffo-specific URL filtering, ID extraction, and page parsing.

```java
@Slf4j
@Component
public class InstaffoStrategy extends SitemapScrapeStrategy {

    private static final Pattern URL_PATTERN =
        Pattern.compile("^https://jobs\\.instaffo\\.com/en/job/.+$");
    private static final Pattern EXTERNAL_ID_PATTERN =
        Pattern.compile("-([a-f0-9]{12})$");
    private static final Pattern SALARY_PATTERN =
        Pattern.compile("(\\d{1,3}(?:,\\d{3}))\\s*[-–]\\s*(\\d{1,3}(?:,\\d{3}))\\s*€");

    @Override public String name() { return "instaffo"; }
    @Override protected Pattern urlFilterPattern() { return URL_PATTERN; }
    @Override protected JobSource jobSource() { return JobSource.INSTAFFO; }

    @Override
    protected String extractExternalId(String url) {
        Matcher m = EXTERNAL_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : url.hashCode() + "";
    }

    @Override
    protected Optional<RawAggregatorJob> parsePage(String html, String url, String externalId) {
        // 1. Try JSON-LD JobPosting extraction (primary)
        // 2. Extract salary from visible HTML (supplementary)
        // 3. Fallback to <h1>/<title> if JSON-LD absent
        // Return Optional.empty() if title is null
    }
}
```

**Extensibility examples — future sources reusing `SitemapScrapeStrategy`:**
```java
// Hypothetical future sources:
class StepStoneStrategy extends SitemapScrapeStrategy { ... }
class IndeedSitemapStrategy extends SitemapScrapeStrategy { ... }
class WelcomeToTheJungleStrategy extends SitemapScrapeStrategy { ... }
```

Each only needs to implement: `name()`, `urlFilterPattern()`, `extractExternalId()`, `parsePage()`, `jobSource()`.

### 3.3 Sitemap Parser (Internal Utility)

**Package:** `dev.jobhunter.strategy.aggregator` (package-private)

**Responsibility:** Reusable SAX-based sitemap XML parser used by `SitemapScrapeStrategy`.

```java
class SitemapXmlParser {

    record SitemapEntry(String url, String lastmod) {}

    /** Stream-parse sitemap XML, return all <loc> entries with optional <lastmod> */
    static List<SitemapEntry> parse(String xmlBody);

    /** Handle sitemap index (sitemapindex > sitemap > loc) — returns child sitemap URLs */
    static List<String> parseSitemapIndex(String xmlBody);
}
```

**Design notes:**
- SAX streaming (not DOM) — handles any sitemap size
- Supports both flat sitemaps and sitemap indexes (Instaffo uses index → `sitemap-jobs.xml`)
- URL filtering and ID extraction happen in the strategy layer (not parser)
- Parser is stateless, static methods only

### 3.4 Page Parsing: JSON-LD Extraction

**Instaffo-specific extraction within `InstaffoStrategy.parsePage()`:**

**Extraction Strategy (Priority Order):**

1. **JSON-LD `JobPosting`** (primary — structured, reliable):
   - `title` ← `$.title`
   - `companyName` ← `$.hiringOrganization.name`
   - `datePosted` ← `$.datePosted` (ISO date string, e.g. `"2024-06-06"`)
   - `description` ← `$.description` (HTML string)
   - `location` ← `$.jobLocation[*].address.addressLocality` (joined with `, `)
   - `employmentType` ← `$.employmentType`
   - `identifier.value` ← slug (used for verification against URL-derived externalId)

2. **Visible HTML** (supplementary — for salary):
   - Salary pattern: `<p data-variant="body-m">80,000 - 95,000 €</p>` inside `.metaItem` div
   - Regex: `(\d{1,3}(?:,\d{3}))\s*[-–]\s*(\d{1,3}(?:,\d{3}))\s*€`
   - Parse to `salaryMin`/`salaryMax` as `BigDecimal`, `salaryCurrency` = `"EUR"`
   - If no salary displayed, leave fields null

3. **Fallbacks:**
   - `title` from `<h1>` if JSON-LD missing
   - `companyName` from `<title>` tag (format: `"Job Title at Company Name"`)
   - `location` from `<meta property="og:title">` or JSON-LD `jobLocation`

**Field Mapping to `RawAggregatorJob`:**

| RawAggregatorJob field | Source                                     |
|------------------------|--------------------------------------------|
| `externalId`           | Last 12 hex chars from URL path            |
| `title`               | JSON-LD `$.title`                           |
| `companyName`         | JSON-LD `$.hiringOrganization.name`         |
| `location`            | JSON-LD `$.jobLocation[*].address.addressLocality` (comma-joined) |
| `description`         | JSON-LD `$.description` (HTML)              |
| `applyUrl`            | The job page URL itself                     |
| `postedDate`          | JSON-LD `$.datePosted` → `LocalDate`        |
| `salaryMin`           | HTML regex extraction → `BigDecimal`        |
| `salaryMax`           | HTML regex extraction → `BigDecimal`        |
| `salaryCurrency`      | `"EUR"` (when salary found) or `null`       |
| `rawJson`             | Full JSON-LD block as string                |

### 3.5 Configuration

**YAML config in `application.yaml`:**

```yaml
aggregator:
  sources:
    - name: instaffo
      strategy: instaffo
      job-source: INSTAFFO
      discovery-source: INSTAFFO
      url: "https://jobs.instaffo.com/sitemap-jobs.xml"
      frequency-hours: 8
      max-results: 50
      visa-exempt: true
      config:
        delayBetweenMs: "400"
        maxScrapePerRun: "50"
```

**Config Parameters:**

| Parameter        | Type   | Default | Purpose                                          |
|------------------|--------|---------|--------------------------------------------------|
| `url`            | String | —       | Sitemap XML URL                                  |
| `frequency-hours`| int    | 8       | How often pipeline runs this source              |
| `max-results`    | int    | 50      | Max jobs returned per fetch (limits FetchResult)  |
| `visa-exempt`    | bool   | true    | Instaffo targets expats; skip visa checks        |
| `delayBetweenMs` | int    | 400     | Delay (ms) between individual page scrapes       |
| `maxScrapePerRun`| int    | 50      | Max pages to scrape per execution (rate safety)  |

**Enum Additions Required:**
- `JobSource.INSTAFFO` — added to enum and `AGGREGATORS` list
- `DiscoverySource.INSTAFFO` — added to enum

## 4. Data Flow

| Step | Component                    | Action                                           | Next                  |
|------|------------------------------|--------------------------------------------------|-----------------------|
| 1    | PipelineScheduler            | Picks up instaffo `SourceConfig` from allSources | AggregatorIngestionService |
| 2    | AggregatorIngestionService   | Calls `source.strategy().fetch(context)`         | InstaffoStrategy      |
| 3    | InstaffoStrategy             | Fetches sitemap XML via WebClient                | InstaffoSitemapParser |
| 4    | InstaffoSitemapParser        | SAX-parses XML, filters `/en/job/` URLs, extracts UUIDs | InstaffoStrategy |
| 5    | InstaffoStrategy             | Filters out already-known externalIds (from context config) | InstaffoJobDetailScraper |
| 6    | InstaffoJobDetailScraper     | For each new URL: GET page, parse JSON-LD + salary | InstaffoStrategy |
| 7    | InstaffoStrategy             | Sleeps `delayBetweenMs` between pages; stops at `maxScrapePerRun` | FetchResult |
| 8    | InstaffoStrategy             | Returns `FetchResult.success(jobs)`              | AggregatorIngestionService |
| 9    | AggregatorIngestionService   | Dedup (source+externalId), fingerprint check     | Filter Chain          |
| 10   | Filter Chain                 | Language → Role → Location → YOE → Visa         | JobPostingRepository  |
| 11   | JobPostingRepository         | Persist passing jobs as `JobPosting` entities    | ScoringScheduler      |
| 12   | ScoringScheduler             | Score new jobs post-ingestion                    | Done                  |

**Incremental Optimization:**

The `InstaffoStrategy.fetch()` method pre-loads known externalIds for `INSTAFFO` source at the start of each run. This set is used to skip detail page fetches for jobs already in the database, reducing HTTP requests from ~988 to only truly new jobs (typically 5-20 per 8-hour cycle).

Implementation approach: pass the set via `FetchContext.config()` map (key: `"knownExternalIds"`) populated by a custom `buildContext()` in `InstaffoSourceConfig`, OR query repository inside the strategy directly (simpler, accepts the coupling).

**Recommended:** Query repository inside strategy via constructor-injected `JobPostingRepository`. This matches the existing pattern where strategies have Spring-managed dependencies.

## 5. Error Handling

| Scenario                    | Detection                          | Response                                    | Recovery                       |
|-----------------------------|------------------------------------|---------------------------------------------|--------------------------------|
| Sitemap fetch timeout/5xx   | WebClient timeout (30s) or 5xx     | Return `FetchResult.error(message)`         | Retry next scheduled run       |
| Sitemap XML parse error     | SAXException                       | Return `FetchResult.error(message)`         | Log + skip run                 |
| Detail page 404/410         | HTTP status code                   | `Optional.empty()` — skip job silently      | Job removed from Instaffo      |
| Detail page 429             | HTTP 429 + optional Retry-After    | Stop scraping remaining pages immediately   | Return partial results as success |
| Detail page 5xx             | HTTP status >= 500                 | Skip this job, increment error counter      | Continue with next job         |
| Detail page timeout         | WebClient timeout (15s)            | Skip this job, log warning                  | Continue with next job         |
| JSON-LD missing/malformed   | JSON parse exception or missing `@type` | Fall back to HTML selectors            | Title from `<h1>`, company from `<title>` |
| JSON-LD title is null       | Field check                        | Skip job entirely (cannot create valid posting) | Log at WARN level          |
| Salary parse failure        | Regex no match                     | Set salaryMin/Max/Currency to null          | Job created without salary     |
| Rate limit during sitemap   | 429 on sitemap URL                 | Return `FetchResult.rateLimited()`          | Pipeline records RATE_LIMITED  |

**Error Budget:** If >30% of detail page fetches fail in a single run, log ERROR and return partial results. This prevents a site-wide outage from producing excessive logs.

**Partial Success:** The strategy returns all successfully-scraped jobs even if some pages failed. `FetchResult.success(partialJobs)` is valid — the ingestion service handles any non-empty list normally.

## 6. Performance Considerations

### Concurrency
- **Single-threaded scraping**: Detail pages fetched sequentially with configurable delay (400ms default). Instaffo has no published rate limit, but aggressive parallel scraping risks IP blocks.
- **Sitemap fetch**: Single GET, ~130KB XML response (fast parse <500ms).
- **Pipeline parallelism**: Instaffo runs in parallel with other sources via PipelineScheduler's executor pool.

### Caching
- **Sitemap caching**: Not cached between runs (8h frequency, sitemap changes daily). In-memory only during single run.
- **externalId set**: Loaded once per run from DB. For ~1000 jobs this is trivial (single query, <10ms).

### Incremental Fetching
- First run: scrapes up to `maxScrapePerRun` (50) new pages
- Subsequent runs: only new jobs appear in sitemap that aren't in DB
- Steady state: 5-20 new jobs per day → 5-20 page fetches per run
- Full initial backfill: requires 20 runs (50/run × 20 = 1000 jobs) over ~7 days

### Execution Time Estimates
- Sitemap fetch + parse: ~2 seconds
- 50 detail pages × (fetch 1s + delay 400ms): ~70 seconds
- Total per run: ~75 seconds (worst case first run), ~15 seconds steady state

### Memory
- SAX parser: streaming, no full DOM in memory
- Job list: 50 `RawAggregatorJob` records × ~5KB each = ~250KB peak

## 7. Testing Strategy

### Unit Tests

**`InstaffoSitemapParserTest`**
- Parse valid sitemap XML fixture → correct URL list extracted
- Filter out `/de/job/` URLs (only `/en/` kept)
- Extract correct 12-char hex UUID from various slug patterns
- Handle empty sitemap gracefully
- Handle malformed XML (unclosed tags) → appropriate exception
- Parse `<lastmod>` timestamps correctly

**`InstaffoJobDetailScraperTest`**
- Parse page with full JSON-LD → all fields mapped correctly
- Parse page with salary in HTML → salaryMin/Max/Currency extracted
- Parse page without salary → null salary fields, job still valid
- Parse page with multiple `jobLocation` entries → comma-joined string
- Handle missing JSON-LD → fallback to `<h1>` and `<title>`
- Handle null title in JSON-LD → return `Optional.empty()`
- Handle malformed JSON-LD → fall back to HTML selectors

**`InstaffoStrategyTest`**
- Full happy path: mock sitemap + 3 detail pages → 3 RawAggregatorJobs
- Incremental: 5 URLs in sitemap, 3 already known → only 2 fetched
- Rate limit on detail page (429) → stops early, returns partial results
- 404 on detail page → skipped silently, other jobs returned
- maxScrapePerRun=2 → only first 2 new jobs scraped
- Sitemap fetch failure → FetchResult.error()
- Delay between fetches: verify timing (use Clock abstraction or Thread.sleep mock)

**Fixtures Required:**
- `instaffo-sitemap.xml` — sample sitemap with 5 `/en/` + 5 `/de/` entries
- `instaffo-job-with-salary.html` — full job page HTML with JSON-LD and salary display
- `instaffo-job-without-salary.html` — job page with JSON-LD but no salary
- `instaffo-job-no-jsonld.html` — edge case page without JSON-LD block

### Integration Tests

**`InstaffoStrategyIntegrationTest`** (WireMock-based, `@Tag("integration")`):
- WireMock serves sitemap + detail page responses
- Verify end-to-end: sitemap parse → detail scrape → FetchResult with correct RawAggregatorJobs
- Verify rate limiting: detail page returns 429 → strategy stops gracefully
- Verify timeout handling: slow response → timeout and skip

### End-to-End Validation
- Manual trigger: `POST /api/admin/crawl` → verify INSTAFFO jobs appear in DB
- Verify filter chain processes Instaffo jobs correctly (German descriptions → LanguageFilter SKIP)
- Verify scoring runs on newly ingested Instaffo jobs
- Verify fingerprint dedup catches cross-source duplicates

## 8. Dependencies

### External
| Dependency      | Purpose                              | Already in classpath |
|-----------------|--------------------------------------|---------------------|
| Jsoup 1.18.1    | HTML parsing, CSS selector queries   | Yes                 |
| WebClient       | HTTP GET for sitemap + detail pages  | Yes                 |
| Jackson         | JSON-LD parsing (ObjectMapper)       | Yes                 |
| javax.xml (SAX) | XML sitemap parsing                  | Yes (JDK built-in) |

### Internal
| Component                        | Interaction                                    |
|----------------------------------|------------------------------------------------|
| `FetchStrategy` interface        | `SitemapScrapeStrategy` implements this        |
| `SitemapScrapeStrategy`          | Abstract base — orchestration, incremental, rate limit |
| `InstaffoStrategy`               | Concrete subclass — URL pattern, ID extraction, page parsing |
| `SitemapXmlParser`               | Reusable SAX utility (package-private)         |
| `FetchContext`                   | Carries config (url, maxResults, config map)   |
| `FetchResult`                    | Returned from fetch(), consumed by ingestion   |
| `RawAggregatorJob`              | Output record from detail scraping             |
| `StrategyRegistry`              | Auto-discovers InstaffoStrategy by `name()`    |
| `DynamicSourceConfigLoader`     | Reads YAML config, wires strategy via registry |
| `AggregatorIngestionServiceImpl`| Handles dedup, filter, persist (unchanged)     |
| `PipelineScheduler`             | Executes source in parallel (unchanged)        |
| `JobPostingRepository`          | Used for incremental skip check (externalId)   |
| `JobSource` enum                | New `INSTAFFO` value required                  |
| `DiscoverySource` enum          | New `INSTAFFO` value required                  |

### No Changes Required To
- `AggregatorIngestionServiceImpl` — works via existing interfaces
- `PipelineScheduler` — auto-discovers new source via `allSources` bean
- `DynamicSourceConfigLoader` — already supports arbitrary strategy names
- Filter chain (Language, Role, Location, YOE, Visa, Dedup) — works on any `RawAggregatorJob`
- `StrategyRegistry` — auto-registers any `@Component` implementing `FetchStrategy`

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| **Architecture** | Abstract `SitemapScrapeStrategy` base + concrete subclass | Reusable for future sitemap sources (StepStone, WelcomeToTheJungle, etc.) | Monolithic InstaffoStrategy | Small upfront cost; pays back on 2nd source integration |
| Data extraction | JSON-LD primary | Structured, schema.org standard, resilient to CSS changes | HTML selectors only, RSC flight data parsing | JSON-LD may lag behind displayed data; HTML fallback covers gaps |
| XML parser | SAX (streaming) | Memory-efficient for ~2000-entry sitemap | DOM parser, regex | Slightly more code than DOM, but scales if sitemap grows |
| Salary extraction | HTML regex from visible content | JSON-LD has no `baseSalary`; RSC data is unstable | RSC flight data JSON parsing, no salary at all | Regex fragile to format changes; acceptable since salary is optional |
| URL filtering | Configurable `Pattern` per source | Each source has different URL structure; pattern injected via abstract method | Hardcoded filter in base class | No extra complexity; subclass provides one regex |
| ExternalId | Configurable extraction per source | Different sites use different ID schemes (UUID, numeric, slug) | Universal heuristic | Each subclass implements 3-line method |
| Incremental fetch | Query DB for known externalIds before scraping | Avoids re-scraping 988 existing pages every run | Sitemap `<lastmod>` comparison, If-Modified-Since | DB query is simpler and deterministic; lastmod requires persisting timestamps |
| Rate limiting | Sequential + configurable delay (YAML) | Simple, predictable, respectful; tunable per-source without code change | Token bucket, adaptive backoff | May be slower than needed; 400ms × 50 = 20s delay overhead, acceptable |
| Registration | YAML config + strategy `@Component` | Consistent with arbeitnow/berlinstartupjobs pattern; frequency/delay changeable without redeploy | Dedicated `@Component` SourceConfig bean | YAML more configurable (change frequency without code change) |

## Extensibility

### Adding a New Sitemap-Based Source

To add a new source (e.g., WelcomeToTheJungle):

1. **Create subclass** (1 file, ~80 lines):
   ```java
   @Component
   public class WelcomeToTheJungleStrategy extends SitemapScrapeStrategy {
       @Override public String name() { return "welcometothejungle"; }
       @Override protected Pattern urlFilterPattern() { return Pattern.compile("^.*/en/companies/.+/jobs/.+$"); }
       @Override protected JobSource jobSource() { return JobSource.WELCOME_TO_THE_JUNGLE; }
       @Override protected String extractExternalId(String url) { /* extract slug */ }
       @Override protected Optional<RawAggregatorJob> parsePage(String html, String url, String externalId) { /* site-specific parsing */ }
   }
   ```

2. **Add enum value**: `JobSource.WELCOME_TO_THE_JUNGLE`

3. **Add YAML config**:
   ```yaml
   - name: welcometothejungle
     strategy: welcometothejungle
     job-source: WELCOME_TO_THE_JUNGLE
     url: "https://www.welcometothejungle.com/sitemap-jobs.xml"
     frequency-hours: 12
     config:
       delayBetweenMs: "500"
       maxScrapePerRun: "30"
   ```

**Zero changes to:** base class, pipeline, ingestion, filters, scheduler.

### Configuration-Driven Behavior

All tunable parameters are externalized to YAML:

| Parameter | Where | Effect |
|-----------|-------|--------|
| `frequency-hours` | YAML source config | How often source runs |
| `max-results` | YAML source config | Cap on FetchResult size |
| `delayBetweenMs` | YAML config map | Rate limit between pages |
| `maxScrapePerRun` | YAML config map | Max new pages per execution |
| `visa-exempt` | YAML source config | Skip visa filter for expat portals |
| `url` | YAML source config | Sitemap URL (change without code) |

Changing any parameter = restart API (Spring Boot) — no recompile needed.

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| HTML structure changes (Next.js deploy) | Salary extraction breaks silently | Medium | JSON-LD primary (stable schema.org). Monitor parse failures per run. Alert if >50% pages yield no data. |
| Instaffo blocks scraping (IP ban) | No new jobs ingested | Low | Respectful delay (400ms), realistic User-Agent, max 50 pages/run. Can increase delay if needed. |
| Sitemap grows very large (>5000 entries) | Longer parse time, more memory | Low | SAX streaming handles any size. MaxScrapePerRun caps work per execution. |
| JSON-LD removed from pages | Primary extraction path fails | Low | Fallback to `<h1>` + `<title>` + HTML selectors. Log WARN, degrade gracefully. |
| Salary format changes (e.g., "80k-95k €") | Salary regex fails, null salary | Medium | Salary is optional enrichment. Multiple regex patterns as fallback. |
| Cross-source duplicates overwhelm dedup | Same jobs from Instaffo + company ATS create noise | Low | Existing fingerprint dedup (title+company+location) handles this. Instaffo enriches existing entries. |
| Rate of new jobs exceeds maxScrapePerRun | Backlog accumulates | Low | Increase maxScrapePerRun or frequency. 50/run at 8h = 150/day capacity vs ~10-20 new/day actual. |

## Data Model

No new entities required. Instaffo jobs flow into existing `JobPosting` entity via `AggregatorIngestionServiceImpl`.

| Entity | Fields used | Values for Instaffo |
|--------|-------------|---------------------|
| `JobPosting` | `source`, `externalId`, `title`, `companyName`, `location`, `description`, `applyUrl`, `postedDate`, `salaryMin`, `salaryMax`, `salaryCurrency` | `INSTAFFO`, 12-char hex, from JSON-LD, from JSON-LD, comma-joined cities, HTML from JSON-LD, page URL, ISO date, BigDecimal or null, BigDecimal or null, `"EUR"` or null |
| `AggregatorRun` | `sourceName`, `status`, `fetched`, `created`, `filtered`, `errors` | `"instaffo"`, from FetchResult status, count scraped, count passing filters, count filtered out, count fetch failures |
| `Company` | `name`, `status` | From JSON-LD `hiringOrganization.name`, `DISCOVERED` |

**Enum additions (schema-safe, no migration needed):**
- `JobSource.INSTAFFO` — VARCHAR column, no DDL change
- `DiscoverySource.INSTAFFO` — VARCHAR column, no DDL change
