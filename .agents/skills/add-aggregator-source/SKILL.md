---
description: Add a new job aggregator source (API or sitemap-based) to the JobHunter crawl pipeline
when_to_use: User asks to integrate a new job aggregator (e.g. "add X strategy", "crawl jobs from Y")
---

## Context

Aggregator sources (LinkedIn, Arbeitnow, BuiltInEurope, Instaffo, etc.) pull jobs from third-party
job boards instead of company ATS endpoints. Adding one requires changes in 5 files plus a Spring
`@Component` strategy class. Agents miss the dual-enum registration and corrupt commits by including
pre-existing `application.yaml` changes.

## Steps

### 1. Determine strategy base class

Two patterns exist:

**A. REST API aggregator** (like BuiltInEurope): implement `FetchStrategy` directly.
- `fetch(FetchContext context)` paginates API, returns `FetchResult.ofAggregator(jobs, elapsed)`
- `supports(AtsType)` returns `false`
- `name()` returns the lowercase strategy key (matches `application.yaml`)

**B. Sitemap-based aggregator** (like Instaffo): extend `SitemapScrapeStrategy`.
- Override `urlFilterPattern()`, `jobSource()`, `extractExternalId(url)`, `parsePage(html, url, externalId)`
- Constructor: `super(webClient, jobPostingRepository)`

### 2. Create the strategy class

Path: `api/src/main/java/dev/jobhunter/strategy/aggregator/{Name}Strategy.java`

```java
@Slf4j
@Component
public class AcmeStrategy implements FetchStrategy {

    private final WebClient webClient;

    public AcmeStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() { return "acme"; }   // matches application.yaml strategy: key

    @Override
    public boolean supports(AtsType type) { return false; }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String apiUrl = (String) context.config().get("url");
        if (apiUrl == null || apiUrl.isBlank()) {
            return FetchResult.error("Acme config requires url", elapsed(start));
        }
        // ... pagination loop ...
        if (jobs.isEmpty()) return FetchResult.empty(elapsed(start));
        return FetchResult.ofAggregator(jobs, elapsed(start));
    }

    private long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }
}
```

Key: use `FetchResult.error(msg, elapsed)` (not `.empty()`) for config/fetch failures so `consecutiveErrors` increments. Use `.empty()` only for "no results, try again later".

### 3. Register in JobSource enum

File: `api/src/main/java/dev/jobhunter/model/enums/JobSource.java`

- Add enum value in the `// Aggregator sources` comment section
- **CRITICAL**: add to the `AGGREGATORS` list — this is a static field, NOT auto-populated from the enum line

```java
// Aggregator sources
LINKEDIN, INDEED, BERLIN_STARTUP_JOBS, ARBEITNOW, CAREERS_IN_GOTHENBURG, INSTAFFO, BUILTIN_EUROPE, ACME,

private static final List<JobSource> AGGREGATORS = List.of(
    LINKEDIN, INDEED, BERLIN_STARTUP_JOBS, ARBEITNOW, CAREERS_IN_GOTHENBURG, INSTAFFO, BUILTIN_EUROPE, ACME
);
```

### 4. Register in DiscoverySource enum

File: `api/src/main/java/dev/jobhunter/model/enums/DiscoverySource.java`

```java
INSTAFFO,
BUILTIN_EUROPE,
ACME    // add here
```

### 5. Add source block to application.yaml

File: `api/src/main/resources/application.yaml` — add under `aggregator-sources:` list.

```yaml
    - name: acme                        # lowercase, human-readable
      strategy: acme                    # must match Strategy.name()
      job-source: ACME                  # JobSource enum value
      discovery-source: ACME           # DiscoverySource enum value
      url: "https://api.acme.com/jobs"
      frequency-hours: 6               # informational only, not enforced
      max-results: 500
      visa-exempt: false               # true = skip visa sponsorship check
      config:                          # optional extra params passed to FetchContext.config()
        queries: "engineer,developer"  # comma-separated → List<String> via YamlSourceConfig
```

`queries` in config → `YamlSourceConfig.buildContext()` parses comma-split → `FetchContext.keywords()`.

### 6. Build and test

```bash
cd api
JAVA_HOME=$HOME/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew compileJava
JAVA_HOME=... ./gradlew test --tests "dev.jobhunter.strategy.aggregator.*"
```

### 7. Commit (selective staging — CRITICAL)

`application.yaml` almost always contains pre-existing uncommitted changes (hikari pool size,
`crawl.concurrency`, etc.) alongside the new source block. Never commit the file wholesale.

```bash
git diff --cached --stat          # verify nothing accidentally staged
git add api/src/main/java/.../AcmeStrategy.java
git add api/src/main/java/.../JobSource.java
git add api/src/main/java/.../DiscoverySource.java
# For application.yaml: only stage the new source block, not pre-existing changes
git add -p api/src/main/resources/application.yaml   # interactive hunk selection
git diff --cached --stat          # verify only your 4 files staged
git commit -m "feat: add AcmeStrategy aggregator"
```

## Gotchas

- **AGGREGATORS list is not auto-populated** — adding to the enum line alone is insufficient. The
  `AGGREGATORS` static field in `JobSource.java` must be manually updated or `isAggregator()` returns
  false and visa/dedup logic breaks.
- **`application.yaml` pre-existing changes**: hikari pool, crawl.concurrency, etc. are routinely
  left unstaged from previous sessions. Always use `git add -p` for application.yaml.
- **`FetchResult.error` vs `.empty`**: use `.error()` for config/HTTP failures (increments
  `consecutiveErrors` → endpoint auto-deactivates at 10); use `.empty()` only when API returned
  cleanly but found no jobs.
- **`supports(AtsType)` must return false** for aggregators — they're not ATS-type bound. If it
  returns true for any AtsType, the StrategyRegistry may route ATS endpoints to this strategy.
- **`DynamicSourceConfigLoader`** reads `strategy:` key from yaml and resolves via
  `StrategyRegistry.getByName(name)`. If strategy name doesn't match `Strategy.name()`, it logs a
  warning and skips — no exception, silent failure.
- **Workday shard IDs**: if the aggregator discovers Workday URLs, the `ats_shard_id` column must be
  set separately — a null shard builds `wdnull` in the URL and silently returns empty.

## Templates

### REST API strategy skeleton (with pagination + dedup)

```java
@Override
public FetchResult fetch(FetchContext context) {
    Instant start = Instant.now();
    String apiUrl = (String) context.config().get("url");
    if (apiUrl == null) return FetchResult.error("url required", elapsed(start));

    List<String> keywords = context.keywords();
    if (keywords == null || keywords.isEmpty()) keywords = List.of("");

    Map<String, JsonNode> seen = new LinkedHashMap<>();  // dedup by job id

    for (String kw : keywords) {
        if (seen.size() >= context.maxResults()) break;
        int page = 0;
        while (seen.size() < context.maxResults()) {
            JsonNode resp = callApi(apiUrl, kw, page++);
            JsonNode jobs = resp.path("jobs");
            if (!jobs.isArray() || jobs.size() == 0) break;
            for (JsonNode job : jobs) {
                seen.putIfAbsent(job.path("id").asText(), job);
            }
            if (jobs.size() < PAGE_SIZE) break;  // last page
        }
    }

    if (seen.isEmpty()) return FetchResult.empty(elapsed(start));

    List<RawAggregatorJob> result = seen.values().stream()
        .map(this::mapJob)
        .filter(Objects::nonNull)
        .toList();

    return FetchResult.ofAggregator(result, elapsed(start));
}
```

### application.yaml entry (with queries fan-out)

```yaml
    - name: myaggregator
      strategy: myaggregator
      job-source: MY_AGGREGATOR
      discovery-source: MY_AGGREGATOR
      url: "https://api.example.com/search"
      frequency-hours: 6
      max-results: 500
      config:
        queries: "software engineer,backend developer,java developer"
```
