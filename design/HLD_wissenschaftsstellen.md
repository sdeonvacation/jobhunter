# HLD: Generic University-Board Abstraction + wissenschaftsstellen.de (First Board) + Source-Scoped Filter Overrides

## Tech Stack

| Category  | Technology            | Purpose                                                              |
| --------- | --------------------- | -------------------------------------------------------------------- |
| Language  | Java 21 (Temurin)     | Existing `api/` module; no new runtime                                |
| Framework | Spring Boot 3.3.5     | DI / component scan; strategy auto-registration via `StrategyRegistry` |
| HTTP      | WebClient             | Non-blocking GET of server-rendered listing + detail HTML            |
| Parsing   | Jsoup 1.18.1          | HTML listing rows + detail-page field extraction                      |
| Config    | Spring Boot YAML      | Source wiring in `application.yaml`; named filter profiles + overrides in `profile.yaml` |
| Pattern   | Template method       | Abstract base + thin per-board subclass, mirroring `AbstractAtsStrategy` |
| Testing   | JUnit 5 + AssertJ + Mockito (+ WireMock) | Base-behaviour (fixture board), board parse, profile-resolution tests |

No new dependencies, no Liquibase changelog, no `AtsType` value, no controller change, no dashboard change.

## Binding User Decisions

These are fixed and drive the whole design:

1. **Generic abstraction = abstract base + thin per-board subclass** (template method), placed directly in `strategy/aggregator/` (flat, matching the existing ATS + aggregator strategy layout) — **not** a config-driven DSL. Mirrors the existing `AbstractAtsStrategy` idiom.
2. **Shared logic lives in the base**: category/keyword enumeration, candidate dedup by board id, bounded pagination + per-run scrape, politeness delay, detail-fetch loop with per-page fault tolerance, and `FetchResult` outcome mapping.
3. **Board hooks in the subclass**: listing URL shape (category/keyword/page), listing link extraction, `externalId` extraction, detail field parsing.
4. **The base supports keyword-only boards** (no category concept) — it is not over-fit to wissenschaftsstellen.
5. **Academic filter defaults are YAML-configurable and reusable**: named filter profiles in `profile.yaml` (e.g. `academic-university`) that board sources reference, with inline per-source definition also allowed. Not hardcoded in the base or subclass.
6. **Unchanged:** scope = `Technik/Labor` only; role override **replaces** the global set; language **fully exempt** for the board; overrides apply to the **aggregator path only** (endpoint crawl untouched).

## Components

| Component | Responsibility | Dependencies |
| --------- | -------------- | ------------ |
| `UniversityBoardStrategy` (**new, abstract**, `strategy/aggregator/`) | Template-method base: shared enumeration, id dedup, bounds, politeness, detail loop + fault tolerance, outcome mapping. Declares the board hooks as abstract. | `WebClient`, `FetchStrategy`, Jsoup |
| `WissenschaftsstellenStrategy` (**new, thin subclass**, same package) | Implements only the board hooks: `name`, `category`, listing URL shape, `/stelle/` link extraction, numeric id extraction, detail selectors/mapping | `UniversityBoardStrategy` |
| `FilterOverrides` (**new**, `filter/`) | Value record: role pattern set + `languageExempt` flag; `NONE` = no override | — (pure data) |
| `JobSource` (mod) | Add `WISSENSCHAFTSSTELLEN` enum constant + add to `AGGREGATORS` list | — |
| `DiscoverySource` (mod) | Add `WISSENSCHAFTSSTELLEN` enum constant | — |
| `SourceConfig` (mod) | Add `default FilterOverrides filterOverrides()` returning `NONE` (mirrors `visaExempt()`) | `FilterOverrides` |
| `YamlSourceConfig` (mod) | Carry a resolved `FilterOverrides` field; override `filterOverrides()` | `FilterOverrides` |
| `DynamicSourceConfigLoader` (mod) | Resolve per-source override from `PersonalProfileLoader` by config `name`, pass into `YamlSourceConfig` | `PersonalProfileLoader` |
| `PersonalProfileLoader` (mod) | Parse `filter-profiles` (named) + `source-filter-overrides` (reference or inline), resolve to a per-source `Map<String, FilterOverrides>` | SnakeYAML |
| `RoleRelevanceFilter` / `RoleRelevanceFilterImpl` (mod) | Add per-invocation overload `filter(String, FilterOverrides)`; override replaces global compiled set only when present | `PersonalProfileLoader`, `FilterOverrides` |
| `JobFilterChain` (mod) | Add 4-arg `apply(...)`; skip language step when `languageExempt`; forward override to role filter; keep 3-arg delegating overload | `LanguageFilter`, `RoleRelevanceFilter`, etc. |
| `AggregatorIngestionServiceImpl` (mod) | Pass `source.filterOverrides()` into `jobFilterChain.apply(...)` (single line) | `SourceConfig`, `JobFilterChain` |
| `CrawlService` (**unchanged**) | Endpoint crawl still calls the 3-arg `apply(...)`, i.e. `FilterOverrides.NONE` — global behaviour preserved | — |
| `LanguageFilter` / `LanguageFilterImpl` (**unchanged**) | Language exemption is a chain-level skip (like `visaExempt`), not a filter-impl concern | — |
| `application.yaml` (mod) | New `aggregator.sources[]` entry (category, queries, delays, bounds) | `AggregatorSourceProperties` |
| `profile.yaml` (mod) | New `filter-profiles` (named) + `source-filter-overrides` (reference/inline) blocks | `PersonalProfileLoader` |

## Architecture

```
                    ┌────────────────────────────────────────────────┐
                    │ PipelineScheduler (unchanged)                   │
                    │  runs all SourceConfig beans (concurrent)        │
                    └──────────────┬─────────────────────────────────┘
                                   │ ingest(source)
                                   ▼
                    ┌────────────────────────────────────────────────┐
                    │ AggregatorIngestionServiceImpl.ingest(source)    │
                    │  · source.strategy().fetch(source.buildContext())│
                    │  · dedup L1/L5 + fingerprint enrich (unchanged)  │
                    │  · jobFilterChain.apply(input, true,             │
                    │        source.visaExempt(),                      │
                    │        source.filterOverrides())   ← NEW 4th arg │
                    └──────────┬───────────────────────────┬──────────┘
                               │                           │
                 ┌─────────────▼──────────────────────────────┐  ┌────▼──────────────────┐
                 │  UniversityBoardStrategy (abstract, final  │  │  JobFilterChain        │
                 │   fetch = template method)                  │  │  apply(..., overrides) │
                 │                                             │  │   lang → role → loc    │
                 │  shared: paginate ?seite=N (newest-first)  │  │   → visa → yoe → dedup │
                 │          parse JOBS=[...] listing JSON      │  │   · skip lang if       │
                 │          scope-filter (Technik/Labor)       │  │     overrides.languageExempt│
                 │          dedup by id + bounds + delay       │  │   · role.filter(title, │
                 │          detail fetch + fault tolerance      │  │     overrides)         │
                 │          outcome mapping                    │  │                        │
                 └──────────────┬─────────────────────────────┘  └───────┬──────────────┘
                                │ abstract hooks                          │
                                ▼                                         ▼
                 ┌──────────────────────────────────────┐    role override replaces
                 │  WissenschaftsstellenStrategy (thin)  │    global set (source-scoped)
                 │   name, buildListingUrl (?seite=N),  │
                 │   parseListingJobs (JOBS + anchors), │
                 │   matchesScope (Technik/Labor),      │
                 │   parseDetail (description)          │
                 └──────────────┬───────────────────────┘
                                ▼
                 https://wissenschaftsstellen.de
                 /?seite=N                    (50/page, newest-first)
                 /stelle/<slug>-<numericId>   (full JD description)
```

**Description.** The board is a config-declared aggregator exactly like `stepstone`/`visajobs`: `DynamicSourceConfigLoader` reads the `application.yaml` entry and builds a `YamlSourceConfig` whose `strategy` is resolved by name via `StrategyRegistry` (auto-collected from the `@Component` subclass). The base class is abstract (never registered); each concrete board is a thin `@Component` subclass supplying only the board hooks.

Two structural additions over the existing contract: (a) a `FilterOverrides` value object surfaced on `SourceConfig`, and (b) a 4-arg `JobFilterChain.apply` that threads it through the aggregator ingest path only. Endpoint crawl (`CrawlService`) keeps calling the 3-arg `apply`, which delegates with `FilterOverrides.NONE`, guaranteeing zero change for ATS/direct endpoints.

**Filter override resolution.** `PersonalProfileLoader` parses two new `profile.yaml` blocks — `filter-profiles` (named, reusable) and `source-filter-overrides` (a source either `profile:`-references a named profile or declares its role/language-exempt inline) — and resolves them to a per-source `Map<String, FilterOverrides>`. `DynamicSourceConfigLoader` looks up by config `name` and bakes the result into `YamlSourceConfig`. `SourceConfig.filterOverrides()` defaults to `NONE`, so every source without an override is provably unchanged.

## Interfaces

### `UniversityBoardStrategy` (new abstract class, `strategy/aggregator/UniversityBoardStrategy.java`)

Mirrors `AbstractAtsStrategy` (`strategy/ats/AbstractAtsStrategy.java`): an abstract class implementing `FetchStrategy`, with the orchestration concrete and the board-specific parts abstract.

**Template method (concrete, `final`):**

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `final FetchResult fetch(FetchContext ctx)` | FetchContext | FetchResult | Full orchestration (below). Cannot be overridden by subclasses. | maps to `error` / `rateLimited` / `empty` / `success` |

`fetch` orchestration (all shared):
1. Read `url` from `ctx.config()`; blank → `FetchResult.error(...)`.
2. Read shared bounds from `ctx.config()`: `delayBetweenMs`, `maxScrapePerRun`, `maxPages`, `maxConsecutiveDetailFailures`.
3. **Enumerate listing pages** `?seite=1..maxPages` via `buildListingUrl(baseUrl, page)`; for each page `getHtml(...)` → `parseListingJobs(html)` → keep only records where `matchesScope(record)` is true. Stop a pass early when a page yields no new externalIds.
4. **Dedup** by `BoardJob.externalId()` into a `LinkedHashMap<String, BoardJob>` (insertion-ordered across ascending pages = newest-first).
5. **Bound (newest-first window)**: the board listing is newest-first by default, so pages `1..maxPages` always cover the freshest postings. Keep the first `min(maxScrapePerRun, ctx.maxResults())` scoped candidates in order; postings older than the window age out. No cursor / persisted offset — freshness is a property of the window, not of cross-run increment.
6. **Detail loop**: for each candidate, `getHtml(job.detailUrl())` (with `delayBetweenMs` before each request after the first), `parseDetail(html, job)` → `RawAggregatorJob` (null ⇒ skip); 404/410 → skip + continue; other transport failure → increment consecutive-failure counter, abort after `maxConsecutiveDetailFailures`.
7. **Outcome**: 429 anywhere → `rateLimited`; collected jobs + transport failure → `success(partial)`; jobs empty + transport failure → `error`; jobs empty → `empty`; else `success`.

**Abstract hooks (subclass supplies):**

| Method | Input | Output | Behavior |
| ------ | ----- | ------ | -------- |
| `String name()` | — | `String` | Board strategy name (registry key) |
| `String buildListingUrl(String baseUrl, int page)` | base + page | `String` | Listing URL for page N. For wissenschaftsstellen only the page varies (`?seite=N`); the base walks `1..maxPages` and the board's default order is newest-first |
| `List<BoardJob> parseListingJobs(String html)` | listing HTML | `List<BoardJob>` | Board-neutral listing records parsed from the page (wissenschaftsstellen: the inline `JOBS=[...]` JSON joined with `/stelle/...` anchors to obtain each job's detail URL). Malformed rows skipped |
| `boolean matchesScope(BoardJob job)` | listing record | `boolean` | Scope predicate the base applies before dedup/bounds. Default `true`; a board that wants everything simply returns `true` |
| `RawAggregatorJob parseDetail(String html, BoardJob job)` | detail HTML + listing record | `RawAggregatorJob \| null` | Map the detail page → full job (description from the JD container / JSON-LD; fall back to `job` for title/company/location/applyUrl). `null` ⇒ job skipped |

`BoardJob` (new small record in `strategy/aggregator/`): `String externalId, String title, String companyName, String location, String applyUrl, String detailUrl, Map<String,Object> attributes` — `attributes` carries board-specific listing fields (wissenschaftsstellen: `kategorie`, `befristung`, `arbeitszeit_pct`, `entgeltgruppe`, `tags`, `quelle`, `inst_typ`, `bundesland`, `fachbereich_raw`).

**Shared concrete helpers (protected, non-abstract):** `getHtml(String url, int timeoutSeconds)` (WebClient GET with browser `User-Agent`, throws on non-2xx), `intConfig` / `longConfig` (parse `String` config values with fallbacks), `elapsed(Instant)`. `supportedTypes()` is inherited from `FetchStrategy`'s default (`Set.of()`), so the base does not override it.

**Future board = thin subclass.** A new board implements only `name()`, `buildListingUrl()`, `parseListingJobs()`, `matchesScope()` (default `true`), and `parseDetail()` — i.e. board-specific markup and URL knowledge only. Shared enumeration, dedup, scope filtering, politeness, bounds, fault tolerance, and outcome mapping are all inherited.

### `WissenschaftsstellenStrategy` (new thin `@Component`, `strategy/aggregator/`)

Implements only the hooks (constructor `super(webClient)`; no shared logic reimplemented):

| Hook | Behaviour |
| ---- | --------- |
| `name()` | `"wissenschaftsstellen"` |
| `buildListingUrl(base, page)` | `base + "/?seite=" + page` — the server ignores `kat`/`q`, and the listing is already newest-first |
| `parseListingJobs(html)` | Extract the inline `JOBS=[...]` JSON (50 objects) and the `/stelle/<slug>-<id>` anchors; join by numeric id → `BoardJob(externalId=id, title=titel, companyName=hochschule, location=bundesland, applyUrl=link, detailUrl=absolutized anchor href, attributes={kategorie,befristung,arbeitszeit_pct,entgeltgruppe,tags,quelle,inst_typ,fachbereich_raw})` |
| `matchesScope(job)` | `"Technik/Labor".equals(job.attributes().get("kategorie"))` |
| `parseDetail(html, job)` | Parse the detail page's `JobPosting` JSON-LD block (`<script type="application/ld+json">`) → `RawAggregatorJob`: `description`, `datePosted` → `postedDate`, plus listing fallbacks for title/company/location/applyUrl; structured `baseSalary` (monthly) → `rawJson`, `salaryMin/Max` stay null; `null` only when no JSON-LD and the title is blank |

### `FilterOverrides` (new record, `filter/FilterOverrides.java`) — unchanged from prior design

| Member | Type | Behavior |
| ------ | ---- | -------- |
| `roleIncludePatterns` | `List<String>` | Empty/null ⇒ no role override ⇒ global set used |
| `roleExcludeKeywords` | `List<String>` | Empty/null ⇒ no role override ⇒ global set used |
| `languageExempt` | `boolean` | `true` ⇒ skip the language filter step |
| `NONE` (static) | `FilterOverrides` | `(List.of(), List.of(), false)` — the universal default |
| `hasRoleOverride()` | `boolean` | `roleIncludePatterns != null && !isEmpty` |

### `PersonalProfileLoader` (mod, `service/PersonalProfileLoader.java`)

| Member | Type | Behavior |
| ------ | ---- | -------- |
| `getFilterProfiles()` | `Map<String, FilterOverrides>` | Named profiles parsed from `filter-profiles` (introspection/tests) |
| `getSourceFilterOverrides()` | `Map<String, FilterOverrides>` | Fully-resolved per source name: `profile:` reference resolved (missing ⇒ warn + `NONE`), inline `language-exempt`/`role` keys merged over the reference |

### `SourceConfig` (mod, `source/SourceConfig.java`)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `filterOverrides()` | none | `FilterOverrides` | `default` returns `FilterOverrides.NONE`; `YamlSourceConfig` overrides with the resolved value | None |

### `JobFilterChain` (mod, `filter/JobFilterChain.java`)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `apply(RawJobInput, boolean isAggregator, boolean visaExempt)` | 3-arg (existing callers) | `FilterChainResult` | Delegates to the 4-arg with `FilterOverrides.NONE` (back-compat; `CrawlService` uses this) | fail-open (existing) |
| `apply(RawJobInput, boolean isAggregator, boolean visaExempt, FilterOverrides overrides)` | + override | `FilterChainResult` | Same cascade, but (1) skips `languageFilter.filter(...)` when `overrides.languageExempt()`; (2) calls `roleRelevanceFilter.filter(input.title(), overrides)` | fail-open (existing) |

### `RoleRelevanceFilter` (mod, `filter/RoleRelevanceFilter.java`)

| Method | Input | Output | Behavior | Errors |
| ------ | ----- | ------ | -------- | ------ |
| `filter(String jobTitle)` | title | `FilterResult` | Global compiled include/exclude (unchanged) | None |
| `filter(String jobTitle, FilterOverrides overrides)` | title + override | `FilterResult` | If `overrides != null && overrides.hasRoleOverride()`, use the override include/exclude patterns (compiled on demand, memoizable per value); else delegate to the 1-arg global path | None |

`RoleRelevanceFilterImpl` keeps its constructor-time global compile (and its `IllegalStateException` when `filters.role` is absent) unchanged.

## Data Flow

| Step | Component | Action | Next |
| ---- | --------- | ------ | ---- |
| 1 | AdminController / PipelineScheduler | `POST /api/admin/aggregate/wissenschaftsstellen` (or scheduler tick) → `ingest(source)` | AggregatorIngestionServiceImpl |
| 2 | AggregatorIngestionServiceImpl | `source.strategy().fetch(source.buildContext())` | UniversityBoardStrategy.fetch |
| 3 | UniversityBoardStrategy | Read shared config (`url`, `delayBetweenMs`, `maxScrapePerRun`, `maxPages`, `maxConsecutiveDetailFailures`) | enumerate |
| 4 | UniversityBoardStrategy | Pages `/?seite=1..N` (newest-first) via `buildListingUrl` → `parseListingJobs` (inline `JOBS=[...]` + `/stelle/` anchors) → filter by `matchesScope` (`kategorie == "Technik/Labor"`) | dedup |
| 5 | UniversityBoardStrategy | Dedup by `BoardJob.externalId()`; bound by `maxScrapePerRun` and `ctx.maxResults()` | detail loop |
| 6 | UniversityBoardStrategy | Fetch each scoped job's detail page (delay), `parseDetail` → `RawAggregatorJob` (description only available here); per-page fault tolerance; abort after `maxConsecutiveDetailFailures` | FetchResult |
| 7 | AggregatorIngestionServiceImpl | L1 source+externalId → L5 dedupHash → UrlValidator → fingerprint enrich vs ATS | filter chain |
| 8 | JobFilterChain | `apply(input, true, source.visaExempt(), source.filterOverrides())` — language skipped (exempt), role override applied, location/yoe/dedup global | persistence |
| 9 | AggregatorIngestionServiceImpl | Persist `JobPosting` (`source=WISSENSCHAFTSSTELLEN`, `externalId`=numeric id, `dedupHash`, fingerprint, `languageFilter=KEEP`) | scoring / dashboard |

**Error Flows:**
- Missing `url` config → `FetchResult.error("university board config requires url", elapsed)` (base).
- HTTP 429 on any page → `FetchResult.rateLimited(elapsed)` (bounded; retried next cycle via `RetryableWebClientFilter`).
- HTTP 5xx on a listing page before any candidate → `FetchResult.error(...)`; after partial collection → `FetchResult.success(partial)`.
- Detail page 404/410 → skip that detail, continue (mirrors `StepStoneStrategy`).
- `maxConsecutiveDetailFailures` exceeded → abort detail loop, retain collected jobs.
- Detail field absent (location/salary/deadline) → field null, job emitted (never dropped).
- Filter-chain exception → fail-open `KEEP` (existing behaviour, unchanged).

## Data Model

No new entities. Two data sources: the listing `JOBS=[...]` JSON (structured metadata, **no description**) and the detail page (description + confirmatory fields). `BoardJob` is built in `parseListingJobs`; `RawAggregatorJob` is built in `parseDetail`.

| Field | Source | `RawAggregatorJob` field | Transform |
| ----- | ------ | ------------------------ | --------- |
| `id` | listing JSON | `externalId` | Verbatim numeric id (e.g. `42700`); stable dedup key |
| `titel` | listing JSON | `title` | Direct text (mandatory — skip job if blank) |
| `hochschule` | listing JSON | `companyName` | Direct text |
| `bundesland` + JSON-LD `addressCountry` | listing JSON + detail JSON-LD | `location` | Compose as `<bundesland>, <addressCountry>` (e.g. `Bayern, DE`) so the geo-resolver resolves state-named locations (a bare state is unresolvable ⇒ dropped); fall back to the listing value alone when `addressCountry` is absent, and to JSON-LD `addressLocality` when `bundesland` is blank. JSON-LD `addressLocality` is often the employer HQ (observed `Bonn` for a `Berlin` job) — prefer the listing value |
| `link` | listing JSON | `applyUrl` | External employer apply URL (e.g. `th-deg.de/...`); **fallback** = site detail URL when absent |
| `description` | detail JSON-LD | `description` | Full JD (HTML) from the detail page's `JobPosting` JSON-LD block; HTML stripped to text for the pipeline — the only field exclusive to the detail page |
| `datePosted` | detail JSON-LD | `postedDate` | **Left `null`** (the real date is kept in `rawJson` only). Aggregator convention: aggregator rows surface via `discoveredDate`, and a non-null `postedDate` makes the shared "today" query require `postedDate >= yesterday` (`JobPostingRepository.java:222-225`), which permanently hides board postings older than a day from the digest |
| `entgeltgruppe`, `befristung`, `arbeitszeit_pct`, `tags`, `frist`, `kategorie`, `quelle`, `inst_typ`, `fachbereich_raw`, `baseSalary`, `employmentType` | listing JSON + detail JSON-LD | `rawJson` | JSON map for traceability; `salaryMin/Max/Currency` stay `null` (structured `baseSalary` is **monthly**, see Decisions) |

## Configuration Contract

### `application.yaml` — `aggregator.sources[]` entry

```yaml
- name: wissenschaftsstellen
  strategy: wissenschaftsstellen        # resolves the thin subclass bean by name
  job-source: WISSENSCHAFTSSTELLEN
  discovery-source: WISSENSCHAFTSSTELLEN
  url: "https://wissenschaftsstellen.de"
  frequency-hours: 12                   # informational only
  max-results: 300                      # hard job cap across the whole run
  config:
    delayBetweenMs: "1000"              # base-owned
    maxScrapePerRun: "120"              # base-owned
    maxPages: "20"                      # base-owned; 50 jobs/page → ~1000 listings scanned
    maxConsecutiveDetailFailures: "5"   # base-owned
```

Shared bounds (`delayBetweenMs`, `maxScrapePerRun`, `maxPages`, `maxConsecutiveDetailFailures`) are read by the base from `context.config()`; `max-results` flows through `context.maxResults()`. There is **no** `category`/`queries` config: the server ignores `kat`/`q`, so the Technik/Labor scope is the board predicate in `matchesScope()`. (A future board with a configurable scope may read it from `config` instead.)

> **Note:** `YamlSourceConfig.buildContext()` hardcodes `FetchContext.maxPages = 3` (`YamlSourceConfig.java:40`). Do **not** use `context.maxPages()` for the pagination bound; the base reads the `maxPages` config key (above), otherwise the effective bound is 3.

### `profile.yaml` — reusable filter profiles + per-source references (canonical filter-config home)

```yaml
# Named, reusable filter profiles (mechanism is in PersonalProfileLoader; policy is here).
filter-profiles:
  academic-university:
    language-exempt: true
    role:
      include-patterns:
        - "softwareentwickler"
        - "\\bentwickler\\b"
        - "software\\s+engineer"
        - "research\\s+software"
        - "it[-\\s]?administrator"
        - "systemadministrator"
        - "data\\s+(scientist|engineer)"
        - "machine\\s+learning"
        - "\\bml\\b"
        - "\\bki\\b"              # künstliche Intelligenz
        - "\\bai\\b"
        - "backend"
        - "full[\\s-]?stack"
        - "informatik"
        - "\\bit\\b"
        - "software"
        - "engineer"
        - "cloud"
        - "devops"
        - "datenbank"
        - "hpc"
        - "scientific\\s+computing"
        - "computational"
      exclude-keywords: []        # empty: no phd/promotion/student exclusions (binding decision)

# Per-source overrides: either reference a named profile, or define inline.
source-filter-overrides:
  wissenschaftsstellen:
    profile: academic-university      # reference (reusable)
  # inline example (no profile reference):
  # some-future-board:
  #   language-exempt: true
  #   role:
  #     include-patterns: [ "...", "..." ]
  #     exclude-keywords: [ "..." ]
```

**Resolution rule (in `PersonalProfileLoader`):**
- `filter-profiles.<name>` → a `FilterOverrides`.
- `source-filter-overrides.<sourceName>` with a `profile:` key → resolve the named profile; any inline `language-exempt` / `role` keys on the same source override the referenced profile's fields (merge).
- `source-filter-overrides.<sourceName>` without `profile:` → build a `FilterOverrides` inline.
- No entry → `FilterOverrides.NONE` (global behaviour).

The override key is the source config **`name`** (lowercase `wissenschaftsstellen`), which `DynamicSourceConfigLoader`, `source.name()`, and `aggregate/{sourceName}` all key on. The exact `include-patterns` list is illustrative/tunable; the **shape** (named profiles + `profile:` reference + inline role/language-exempt) and the **empty-exclude rule** are binding.

## Title Translation (post-filter, pre-save)

Optional, source-scoped: translate stored job titles to English. German academic titles are the norm on this board, so English titles make the digest/list scannable and align keywords with the English profile.

**Placement.** In `AggregatorIngestionServiceImpl.ingest(...)`, translation happens **after** the dedup/language/role/location/visa/YOE filter chain and **before** the `JobPosting` is saved. The role filter therefore keeps operating on the original German title (no pattern changes), and only jobs that are actually stored are translated (typically 10–30/run, not the ~120 fetched).

**Cost model.** Titles are translated in **chunks of up to 10 per AI call** (one call per chunk) — not one call per job, and not one giant call. Verified live: the configured backends need ~4–5s per title, and a single 30-title request exceeded the 60s client timeout (primary `model=auto` router: 44.5s for just 10 titles; zen fallback: 87.6s for 30). A 30-title run therefore costs ~3 calls, each comfortably inside the timeout. Plus a cache so a unique title is translated at most once ever.

**Components:**

| Component | Responsibility |
| --------- | -------------- |
| `service/JobTitleTranslator.java` (new) | `Map<String,String> translate(JobSource, Collection<String> titles)`. Returns title→English for the misses via ONE batched `extract` call; unknown/blank titles map to themselves; never throws |
| `JobPostingRepository` (modify) | Seed query: distinct `(title, raw_content->>'titleOriginal')` pairs for a source, used to warm the cache on first use per JVM |
| `AggregatorIngestionServiceImpl` (modify) | Two-pass: (A) dedup + filter and collect survivors; (B) if `source.translateTitles()` and survivors exist, one batched translate; (C) build+save with the English title and `rawContent.titleOriginal` = German original |
| `AggregatorSourceProperties.SourceEntry` / `SourceConfig` / `YamlSourceConfig` / `DynamicSourceConfigLoader` (modify) | Per-source `translate-titles` flag (default `false`), mirroring `visaExempt` |

**Persistence of the original.** For translated rows, `rawContent = {"titleOriginal": "<German title>"}`. The `title` column holds English. Non-translated sources keep `rawContent` exactly as today (null for aggregators). The German title therefore survives in the stored JSON.

**Cache.** `JobTitleTranslator` holds a `ConcurrentHashMap<String,String>` and lazily seeds it, once per source per JVM, from `JobPostingRepository` (existing `title`←`rawContent.titleOriginal` pairs). Already-translated titles are free on later runs; a title is translated at most once ever.

**Failure/fallback.** If `aiProvider.isAvailable()` is false or a chunk call throws/returns partial data, the original title is used for every missing entry in **that chunk only** (other chunks proceed), a warning is logged, and ingestion proceeds unchanged. Failures are never cached.

**Backfill.** Translation runs only on insert, so rows ingested while AI was unavailable would otherwise stay in their original language forever. `JobTitleTranslator.translateExisting(JobSource)` — exposed as `POST /api/admin/translate-titles?source=<name>` — selects active rows for the source with no `rawContent.titleOriginal`, batch-translates their titles, and updates **only** rows whose title actually changes (already-English titles are no-ops, so re-running is idempotent). This makes translation recoverable without deleting/re-ingesting rows.

**Config.** `application.yaml` → the `wissenschaftsstellen` source entry gains `translate-titles: true`.

**Identity is unaffected.** `fingerprint` / `dedupHash` / `externalId` are computed from the original title *before* translation, so cross-run and cross-source dedup semantics are unchanged.

**Tests.** `JobTitleTranslatorTest` (single batched call for N misses, cache hit avoids a call, partial response → original for the gap, AI failure → originals and no throw, blank/duplicate handling); `AggregatorIngestionServiceImplTest` (flag on ⇒ translated title + `rawContent.titleOriginal`, one translate call; flag off ⇒ no translator interaction, `rawContent` null); `YamlSourceConfigTest` / `DynamicSourceConfigLoaderTest` for the new flag.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
| -------- | ------ | ------ | ------------ | --------- |
| Abstraction = abstract base + thin subclass | Template-method base in `strategy/aggregator/`, mirroring `AbstractAtsStrategy` | Mirrors the proven `AbstractAtsStrategy` idiom; shared orchestration lives in one place, board markup stays in small testable subclasses; future boards are ~40-line additions | Config-driven DSL (one class driven entirely by YAML) | DSL centralizes but forces a lowest-common-denominator hook set and is hard to special-case per board; template method is more idiomatic to this codebase |
| Shared behaviour in base | Enumeration, id dedup, bounds, politeness, detail loop + fault tolerance, outcome mapping | All boards need identical flow; one implementation removes per-board bugs | Duplicate in each board | Duplication + drift across boards; base gives consistent politeness/bounds guarantees |
| Hooks = listing parse, scope predicate, detail mapping | `name`, `buildListingUrl(base, page)`, `parseListingJobs(html)`, `matchesScope(job)` (default `true`), `parseDetail(html, job)` | The only genuinely board-specific parts are URL shape, page markup, and the scope rule | More/finer hooks | Finer hooks (e.g. per-field selectors) would be over-engineered now and still not cover arbitrary boards |
| Scope filtering is a board predicate, not a URL param | `matchesScope(job)` applied by the base (wissenschaftsstellen: `kategorie == "Technik/Labor"`) | The site ignores `kat`/`q` server-side and filters client-side, so scope must be applied by the crawler after parsing | Server-side `?kat=`/`?q=` filtering | Not available on this board; a keyword-free board simply returns `true`, so the hook stays generic |
| Academic defaults in YAML, not code | Named `filter-profiles` + per-source `profile:` reference / inline in `profile.yaml` | FR10/D2/D3; keywords/flags tunable without code; future boards inherit `academic-university` by reference | Hardcode patterns in the base/subclass | Hardcoding would force code changes per board and duplicate keyword lists |
| Profile resolution in `PersonalProfileLoader` | Loader parses `filter-profiles` + `source-filter-overrides`, resolves to `Map<String, FilterOverrides>` keyed by source name | `profile.yaml` is already loaded there; `DynamicSourceConfigLoader` stays trivial (`getSourceFilterOverrides().getOrDefault(name, NONE)`) | Resolve in `DynamicSourceConfigLoader` | Would spread profile-resolution logic into source wiring; loader owns profile parsing already |
| Override home: `profile.yaml` (not source config) | Filter semantics in `profile.yaml`; source wiring in `application.yaml` | `profile.yaml` is the canonical filter-config home; keeps transport/strategy config out of filter policy | Put patterns in `application.yaml` `config:` block | `profile.yaml` needs a second lookup; but it preserves the "filter config vs source wiring" separation |
| Thread override as a value object | `FilterOverrides` on `SourceConfig`, passed as 4th arg to `JobFilterChain.apply` | Explicit, testable, mirrors the existing `visaExempt` boolean pattern; no hidden global/ThreadLocal state | `sourceName` param + in-filter lookup | Name-coupling + two sources of truth; value object keeps the filter pure |
| Backward compatibility | Keep 3-arg `apply` as a delegating overload returning `NONE` | `CrawlService` and existing tests keep compiling/behaving unchanged; zero risk to endpoint crawl | Break the signature and touch every caller | A small overload is the least-ripple path; the 4-arg is the single real implementation |
| Language exemption = chain-level skip | `JobFilterChain` skips `languageFilter.filter(...)` when `languageExempt`; `LanguageFilter` untouched | Identical to how `visaExempt` skips the visa step; no per-invocation override needed in the language filter | Override inside `LanguageFilter.filter(...)` | Would couple the language filter to a concept it doesn't own; chain-level skip is simpler and already proven |
| Role override = replace, not merge | Override include+exclude **replaces** the global set (empty exclude ⇒ no exclusions) | The global `exclude-keywords` (`phd`, `promotion`, `student`, `support`, `simulation`, `embedded`, …) actively remove relevant academic/technical roles; they must be dropped for this source | Merge override exclude with global exclude | Merging would re-introduce `phd`/`student` exclusions and defeat the purpose |
| `salaryMin/Max` left null | Preserve salary range + Entgeltgruppe in `rawJson` only | German public-sector salaries are **monthly** ("€/Monat"); downstream `OpportunityScorer` treats salary as annual — mapping monthly numbers would fabricate misleading annual figures | Parse + scale ×12 to annual | Scaling assumes a uniform 12-month model and misrepresents the raw datum; honest null + `rawJson` mirrors the existing `VisaJobs` salary decision |
| `postedDate` left `null` (reverted 2026-09-13) | Aggregator rows surface via `discoveredDate`; a non-null `postedDate` forces the digest branch `postedDate >= yesterday`, permanently hiding board postings older than a day (verified live: 30/30 rows invisible in `/api/jobs/today`) | Fixes the digest-invisibility regression and matches every other aggregator source; each job surfaces once, on its discovery day (`discoveredDate` is insert-time, so it does not re-surface) | Set `postedDate` from the JSON-LD `datePosted` | The real posting date is preserved in `rawJson`; digest ordering falls back to score/discovery rather than the board date |
| `applyUrl` = external "Zur Bewerbung" link, fallback detail URL | External employer ATS link drives L5 `dedupHash` + fingerprint enrichment vs ATS jobs | Enables cross-source dedup when the same role is also crawled from the employer's ATS | Detail URL as `applyUrl` | Detail URL would not match the employer ATS apply URL, breaking enrichment |
| `externalId` = numeric `/stelle` id | Verbatim numeric id | Stable across crawls; drives L1 source+externalId dedup; no slug churn | Full detail URL | Slug changes would break dedup; numeric id is the stable key |
| Location carries an explicit country token | `location = <bundesland>, <addressCountry>` (from detail JSON-LD) | The listing gives a German/Austrian/Swiss state (`Bayern`, `Nordrhein-Westfalen`), which `CityCountryResolver` cannot resolve, so `unknown-action: skip` dropped genuine software/IT roles (verified live: 14 drops, all states). Appending the ISO country token makes it resolvable without mislabelling the city | Bare state name; or use JSON-LD `addressLocality` only | Bare state is dropped; `addressLocality` is often the employer HQ (wrong city). Composing keeps the truthful state and adds only the country |
| Override keyed by config `name` (lowercase) | `source-filter-overrides.wissenschaftsstellen` | The loader, `source.name()`, and `aggregate/{sourceName}` all key on the config name | Key by `JobSource` enum name | Enum name (`WISSENSCHAFTSSTELLEN`) is not what the config/loader/controller use; would require a second mapping |
| **Package placement = flat `strategy/aggregator/`** | `UniversityBoardStrategy` + `WissenschaftsstellenStrategy` live directly in `strategy/aggregator/` (no `university/` subpackage) | `AbstractAtsStrategy` sits flat in `strategy/ats/` with its concrete strategies, and `SitemapScrapeStrategy` (an abstract template-method base) already sits flat in `strategy/aggregator/` with its concrete subclass; the codebase has no nested strategy subpackages | `strategy/aggregator/university/` subpackage | A subpackage would group the family and avoid adding to the 12-class flat package, but it would be the only nested strategy subpackage in the repo and diverge from both existing precedents |
| **No `SitemapScrapeStrategy` reuse** | `SitemapScrapeStrategy` is unrelated; the university base does not extend or reuse it | `SitemapScrapeStrategy` is sitemap-driven (fetch `sitemap.xml` → regex-filter `<loc>` URLs → known-id skip) with no category/keyword-pagination model; wissenschaftsstellen's sitemap has no per-URL `lastmod` and enumeration is listing-based | Reuse/extend `SitemapScrapeStrategy` | It shares only the *idea* of a template-method base (abstract `name`/`extractExternalId`/`parsePage` hooks + `final fetch`), which the university base independently mirrors; the enumeration and dedup semantics differ enough that reuse would be forced |
| **Freshness = newest-first bounded window** | The board listing is already newest-first by default (measured: descending numeric id, no sort param needed); the bounded window always covers the freshest postings; older postings age out. No cursor/persisted offset | A page-1 restart with "first N" selection would otherwise re-pick the same head candidates every run and never advance past the first pages (~165) | Cross-run cursor/persisted offset, or full backfill | Postings older than the window are not backfilled — acceptable for a job feed and avoids unbounded crawling; a full backfill, if ever needed, is a separate one-off out of scope |

## Risks

| Risk | Impact | Likelihood | Mitigation |
| ---- | ------ | ---------- | ---------- |
| Abstraction over-fit to one board | Future boards can't fit the hooks | Low | Hooks are minimal and keyword-only is supported; proven by a second fixture subclass test |
| Future board with a different scope rule | Base assumes a scope predicate fits | Low | Scope is a single `matchesScope(job)` hook defaulting to `true`; a board that wants everything inherits the default — the base makes no category/keyword assumption |
| Override leaking globally | Other sources' filter decisions change | Low | `filterOverrides()` defaults to `NONE`; explicit regression test asserting an untagged source uses the global set; `CrawlService` untouched |
| Profile reference to an undefined name | Silent global behaviour on a board | Low | `PersonalProfileLoader` warns + resolves to `NONE`; test asserts missing-profile fallback |
| Client-side-only filtering (V1, resolved) | `kat`/`q` return unfiltered server HTML | Low | Verified live: scope is applied by the crawler via `matchesScope()` after parsing `JOBS=[...]`; no server-side filter is relied upon |
| Large Technik/Labor volume (V2, ~165 pages) | Unbounded crawl / hammering | Medium | Base-owned `maxPages`, `maxScrapePerRun`, `max-results`, `delayBetweenMs`; newest-first window keeps coverage to the freshest postings (no unbounded pagination, no cross-run increment) |
| Detail fields missing (V3) | Null location/salary/deadline | Medium | `parseDetail` tolerates absent fields; job emitted with nulls; description retained for downstream scoring |
| Salary monthly-vs-annual | Misleading scoring input | Low | Salary left null; `rawJson` retains raw text (Decision) |
| External apply URL absent | No L5 dedup / fingerprint enrichment | Low | Fallback to detail URL; still unique and fetchable |
| robots `ai-train=no` | Policy breach if content fed to model training | Low | Crawling is permitted (`search=yes`, `use=reference`); content used only for personal search/ranking, never training |
| Markup drift (listing/detail selectors) | Silent empty crawls | Medium | Board fixture tests lock selectors; `GET /api/admin/health` surfaces empty/error source runs |

## Test Plan

### Unit Tests — Abstraction (base behaviour via a fixture board)

`UniversityBoardStrategyTest` exercises the base through a test-only `FixtureBoard` subclass (no network; `getHtml` overridden/stubbed or WebClient mocked), asserting shared behaviour:

| Scenario | Assertion |
| -------- | --------- |
| Listing pagination | pages requested `?seite=1..N` via `buildListingUrl`; stops at `maxPages` |
| Listing parse | `parseListingJobs` records collected; duplicate `externalId` across pages → one candidate |
| Scope predicate | only `matchesScope == true` records proceed to detail fetch |
| Default scope (match-all) | a fixture board returning `true` keeps all records |
| Per-run scrape bound | `maxScrapePerRun` / `max-results` respected |
| Politeness delay | ≥ `delayBetweenMs` between detail requests |
| Partial success | mid-run transport failure → `success` with already-collected jobs |
| 429 | `rateLimited` |
| 5xx before any candidate | `error` |
| Empty enumeration | `empty` |
| `parseDetail` returns null | job skipped, loop continues |

**Thin-add contract test** (`UniversityBoardStrategyTest` or a small `FixtureBoardBTest`): a minimal second fixture subclass implementing only `buildListingUrl`, `parseListingJobs`, and `parseDetail` (inheriting the `matchesScope` default) proves a new board is a thin addition with no shared-logic reimplementation.

### Unit Tests — wissenschaftsstellen board

`WissenschaftsstellenStrategyTest` (fixture HTML, no network — mirror `StepStoneStrategyTest`):

| Scenario | Assertion |
| -------- | --------- |
| Listing URL shape | `buildListingUrl(base, 3)` == `base + "/?seite=3"` |
| `JOBS=[...]` parsing | 50 records parsed from a fixture listing page; `id`/`titel`/`hochschule`/`bundesland`/`link` mapped |
| Detail URL join | each record's `detailUrl` resolved from the matching `/stelle/<slug>-<id>` anchor |
| Scope filter | only `kategorie == "Technik/Labor"` records kept |
| `externalId` | taken verbatim from JSON `id` (`42700`) |
| Detail mapping | description extracted from the JD container/JSON-LD; title/company/location/applyUrl fall back to the listing record |
| Missing fields (V3) | absent location/salary/deadline → null fields, job still emitted |
| Salary/Entgeltgruppe | captured in `rawJson`, `salaryMin/Max` null |
| `applyUrl` fallback | absent `link` → detail URL used |

### Unit Tests — filter overrides + profile resolution

- `FilterOverridesTest` (new): `NONE` is `(empty, empty, false)`; `hasRoleOverride()` true only when include non-empty.
- `RoleRelevanceFilterImplTest` (extend): `filter(title, overrides)` with override present KEEPs academic titles (`Wissenschaftliche*r Mitarbeiter*in`, `IT-Administrator`); empty exclude does not drop `Promotion`/`PhD`; `NONE`/null → identical to global.
- `JobFilterChainTest` (extend): existing 3-arg tests pass via the delegating overload (no stubbing change); new `languageExempt=true` → `languageFilter.filter` never called; role override forwarded to `roleRelevanceFilter.filter(title, overrides)`.
- `PersonalProfileLoader` profile-resolution test (new/extend): `filter-profiles` parse; `source-filter-overrides` `profile:` reference resolves; inline merges over reference; missing profile → warn + `NONE`; absent source → `NONE`.

### Integration Tests

- `YamlSourceConfigTest` (extend — constructor ripple): `filterOverrides()` returns the passed `FilterOverrides` (and `NONE` when null).
- `DynamicSourceConfigLoaderTest` (extend — loader now takes `PersonalProfileLoader`): mock `getSourceFilterOverrides()`; assert override resolved per source name; unknown name → `NONE`.
- `AggregatorIngestionServiceImplTest` (extend — stub ripple): update `jobFilterChain.apply` mocks from 3-arg to 4-arg; add a test capturing the `FilterOverrides` argument to assert `source.filterOverrides()` is threaded through.
- WireMock aggregator ingest: fixture `SourceConfig` with `WISSENSCHAFTSSTELLEN` + override → `JobPosting` persisted with `source=WISSENSCHAFTSSTELLEN`, German JD not skipped (language-exempt), academic title KEEPs (role override).

### End-to-End Verification

- `POST /api/admin/aggregate/wissenschaftsstellen` against the live site surfaces Technik/Labor software/AI jobs in the dashboard source tab.
- `GET /api/admin/health` shows `wissenschaftsstellen` SUCCESS with expected count.
- Regression: a control run confirms other sources' filter decisions unchanged (e.g. an endpoint crawl of a German-titled role still SKIPs via the global role/language set).

### Non-Functional Tests

| Concern | Requirement |
| ------- | ----------- |
| Politeness | `delayBetweenMs` (default 1000ms) between detail fetches; bounded pages + per-run scrape; respects `robots.txt` `Allow: /` |
| Resilience | Per-page failures don't abort; partial success retained; 429 → `RATE_LIMITED` (retry next cycle) |
| Memory | ≤ `max-results` in-memory candidates; no unbounded buffers |
| Idempotency | Numeric externalId dedup → re-runs produce no duplicates |

## File Change List

| File | Change | Description |
| ---- | ------ | ----------- |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/BoardJob.java` | **New** | Board-neutral listing record: `externalId, title, companyName, location, applyUrl, detailUrl, attributes` |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/UniversityBoardStrategy.java` | **New (abstract)** | Template-method base: shared pagination/dedup/scope/bounds/detail-loop/outcome + abstract hooks |
| `api/src/main/java/dev/jobhunter/strategy/aggregator/WissenschaftsstellenStrategy.java` | **New (thin subclass)** | Board hooks only: `name`, `buildListingUrl` (`?seite=N`), `parseListingJobs` (JOBS JSON + anchors), `matchesScope` (Technik/Labor), `parseDetail` (description) |
| `api/src/main/java/dev/jobhunter/filter/FilterOverrides.java` | **New** | Value record (role patterns + languageExempt) + `NONE` |
| `api/src/main/java/dev/jobhunter/model/enums/JobSource.java` | Modify | Add `WISSENSCHAFTSSTELLEN` + `AGGREGATORS` list |
| `api/src/main/java/dev/jobhunter/model/enums/DiscoverySource.java` | Modify | Add `WISSENSCHAFTSSTELLEN` |
| `api/src/main/java/dev/jobhunter/filter/RoleRelevanceFilter.java` | Modify | Add `filter(String, FilterOverrides)` |
| `api/src/main/java/dev/jobhunter/filter/RoleRelevanceFilterImpl.java` | Modify | Implement override overload |
| `api/src/main/java/dev/jobhunter/filter/JobFilterChain.java` | Modify | 4-arg `apply` + 3-arg delegating overload |
| `api/src/main/java/dev/jobhunter/source/SourceConfig.java` | Modify | `default filterOverrides()` |
| `api/src/main/java/dev/jobhunter/source/YamlSourceConfig.java` | Modify | `FilterOverrides` field + override method |
| `api/src/main/java/dev/jobhunter/source/DynamicSourceConfigLoader.java` | Modify | Inject `PersonalProfileLoader`, resolve override |
| `api/src/main/java/dev/jobhunter/service/PersonalProfileLoader.java` | Modify | Parse `filter-profiles` + `source-filter-overrides` → resolved `Map<String, FilterOverrides>` |
| `api/src/main/java/dev/jobhunter/ingestion/AggregatorIngestionServiceImpl.java` | Modify | Pass `source.filterOverrides()` into `apply` |
| `api/src/main/resources/application.yaml` | Modify | Add `wissenschaftsstellen` source entry |
| `profile.yaml` | Modify | Add `filter-profiles.academic-university` + `source-filter-overrides.wissenschaftsstellen` |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/UniversityBoardStrategyTest.java` | **New** | Base-behaviour via fixture board + keyword-only fixture subclass |
| `api/src/test/java/dev/jobhunter/strategy/aggregator/WissenschaftsstellenStrategyTest.java` | **New** | Board hook/parse tests |
| `api/src/test/java/dev/jobhunter/filter/FilterOverridesTest.java` | **New** | Small record tests |
| `api/src/test/java/dev/jobhunter/filter/{JobFilterChainTest,RoleRelevanceFilterImplTest}.java` | Modify | Override + language-exempt coverage |
| `api/src/test/java/dev/jobhunter/source/{YamlSourceConfigTest,DynamicSourceConfigLoaderTest}.java` | Modify | Constructor/param ripple |
| `api/src/test/java/dev/jobhunter/service/PersonalProfileLoaderTest.java` (if present) or new | Modify/**New** | Profile/reference/inline resolution tests |
| `api/src/test/java/dev/jobhunter/ingestion/AggregatorIngestionServiceImplTest.java` | Modify | 4-arg `apply` stub + override-threading assertion |

> **Not modified:** `CrawlService.java` (still 3-arg `apply`), `LanguageFilter.java` / `LanguageFilterImpl.java`, `StrategyRegistry.java`, `AtsType`, all dedup infrastructure, scoring, dashboard, and no Liquibase changelog.

## Open Items (requirement V1–V5)

| # | Item | Handling |
| - | ---- | -------- |
| V1 | Client-side vs server-side filtering | **RESOLVED (2026-09-13):** `?kat=`/`?q=`/`?fach=` are ignored server-side (byte-identical responses); scope is applied by the crawler via `matchesScope()` |
| V2 | Exact Technik/Labor volume | ~165 pages × 50 jobs; base-owned `maxPages`/`maxScrapePerRun`/`max-results` bounds are mandatory; tune after first live run |
| V3 | Detail-field reliability | Parsing tolerates absent location/salary/deadline; null fields never drop a job |
| V4 | Newest-first sort parameter | **RESOLVED (2026-09-13):** the listing is already newest-first by default (descending id); no sort parameter needed |
| V5 | Detail description source | **RESOLVED (2026-09-13):** the detail page's `JobPosting` JSON-LD block provides `description`, `datePosted`, `jobLocation`, and `baseSalary` — parse that rather than CSS selectors |
