# Plan: StepStone Jobs Ingestion (Aggregator Strategy)

## Context

StepStone is represented by `AtsType.STEPSTONE`, `JobSource.STEPSTONE`, `DiscoverySource.STEPSTONE`, and URL detection, but it is not a working job source. The existing `StepStoneProvider` is discovery-only and currently incompatible with the installed `mcp-stepstone` package. It also creates companies/endpoints, not `JobPosting` rows.

The selected approach is a jobs-only aggregator `FetchStrategy`. Do not repair or modify the StepStone discovery provider in this scope.

## Existing mechanisms

- `FetchStrategy` and `StrategyRegistry` support auto-registered named aggregator strategies.
- `InstaffoStrategy` and `SitemapScrapeStrategy` provide WebClient, Jsoup, JSON-LD, incremental dedup, rate-limit, and delay patterns.
- `JobSource.STEPSTONE` exists but is absent from `JobSource.AGGREGATORS`.
- `AtsDetector` already maps `stepstone.de`, `stepstone.at`, `stepstone.nl`, and `stepstone.be` URLs to `STEPSTONE`.
- StepStone detail URLs use the form `stellenangebote--<slug>--<job_id>-inline.html`.
- Detail pages expose schema.org `JobPosting` JSON-LD with title, description, datePosted, hiring organization, location, and optional salary.
- StepStone search URLs must use the robots-compliant `/jobs/*?q=*` form. Do not paginate in v1.

## Changes

### 1. Add `StepStoneStrategy`

Create:

`api/src/main/java/dev/jobhunter/strategy/aggregator/StepStoneStrategy.java`

Implement `FetchStrategy`, annotate with `@Component`, and return `"stepstone"` from `name()`. Keep `supportedTypes()` empty so ATS endpoint crawls are not routed into this search provider.

Constructor dependencies:

```java
public StepStoneStrategy(WebClient webClient, JobPostingRepository jobPostingRepository)
```

Use a browser-like User-Agent, `Accept-Language`, a default delay of approximately 1500 ms, a default max scrape count of 40, and configurable search/detail timeouts.

`fetch(FetchContext)` should have three phases:

1. **Search harvesting**
   - Read base URL from `context.config().get("url")`.
   - Read keywords from `context.keywords()` and optional city slugs from `context.config().get("cities")`.
   - Build `/jobs/<slug>?q=<encoded-keyword>` or `/jobs/<slug>/in-<city>?q=<encoded-keyword>` URLs.
   - Fetch search pages sequentially, honoring delay.
   - Parse relative and absolute anchors with Jsoup.
   - Keep only links matching `stepstone.(de|at|nl|be)/stellenangebote--...-inline.html`.
   - Store links in a `LinkedHashSet` to deduplicate across keyword/city searches.
   - Handle 429 as rate-limited and 403 as an anti-bot warning.

2. **Incremental deduplication**
   - Extract numeric IDs using `--(\\d+)-inline\\.html`.
   - Load known IDs with `jobPostingRepository.findExternalIdsBySource(JobSource.STEPSTONE)`.
   - Skip known IDs and cap new detail URLs at `maxScrapePerRun`.

3. **Detail scraping**
   - Fetch each detail page with the same headers.
   - Parse `script[type="application/ld+json"]` objects whose `@type` is `JobPosting`.
   - Map to `RawAggregatorJob` in its exact 11-argument order:
     `externalId`, `title`, `companyName`, `location`, `description`, `applyUrl`, `postedDate`, `salaryMin`, `salaryMax`, `salaryCurrency`, `rawJson`.
   - Map company from `hiringOrganization.name`.
   - Map location from `jobLocation[].address.addressLocality`, joined with `", "`.
   - Parse `datePosted` to `LocalDate`.
   - Parse `baseSalary.value.minValue`, `maxValue`, or scalar `value`; only persist salary when `unitText` is `YEAR`.
   - Fall back to `<h1>` if JSON-LD has no title. Skip only pages with no recoverable title.
   - Handle 429 with partial results, 404/410 by skipping, and other failures by continuing with an error count.

Use a non-null URL hash as the external-ID fallback if the expected numeric ID is absent.

### 2. Add StepStone to aggregator sources

Edit `api/src/main/java/dev/jobhunter/model/enums/JobSource.java` and append `STEPSTONE` to the `AGGREGATORS` list. This enables aggregator ingestion, filtering, deduplication, and description backfill behavior.

### 3. Register the dynamic source

Add the following entry inside `aggregator.sources` in `api/src/main/resources/application.yaml`:

```yaml
    - name: stepstone
      strategy: stepstone
      job-source: STEPSTONE
      discovery-source: STEPSTONE
      url: "https://www.stepstone.de"
      frequency-hours: 12
      max-results: 100
      config:
        queries: "java developer,backend engineer,spring boot,kotlin developer"
        cities: "berlin,muenchen,hamburg,frankfurt-am-main,koeln"
        delayBetweenMs: "1500"
        maxScrapePerRun: "40"
```

All `config` values should remain strings because the configuration map is `Map<String, String>`. The existing discovery `stepstone` block remains untouched.

### 4. Add focused tests

Create:

`api/src/test/java/dev/jobhunter/strategy/aggregator/StepStoneStrategyTest.java`

Use WireMock to map search URLs and detail URLs to fixtures. Cover:

- strategy name and empty supported ATS types;
- robots-compliant `?q=` search URL construction;
- relative detail-link harvesting and noise-link rejection;
- JSON-LD mapping for title, company, location, date, salary, raw JSON, and URL;
- numeric external-ID extraction;
- known-ID deduplication;
- `maxScrapePerRun` cap;
- 429 on search and detail, including partial results;
- 404 detail skipping;
- JSON-LD-absent `<h1>` fallback;
- untitled-page skipping;
- empty-query error;
- 403 anti-bot handling;
- cross-city duplicate detail URLs.

## Explicitly out of scope

Do not modify:

- `api/src/main/java/dev/jobhunter/discovery/StepStoneProvider.java`;
- `api/src/main/java/dev/jobhunter/discovery/DiscoveryService.java`;
- the existing discovery `stepstone` configuration.

No database migration is required.

## Verification

```bash
cd api
./gradlew compileJava compileTestJava
./gradlew test --tests "dev.jobhunter.strategy.aggregator.StepStoneStrategyTest"
./gradlew test
```

After restart, verify source registration and execute a manual source crawl:

```bash
curl -s http://localhost:8089/api/admin/aggregators
curl -s -X POST http://localhost:8089/api/admin/aggregate/stepstone
```

Verify persisted StepStone rows have numeric `external_id`, `-inline.html` `apply_url`, and populated title/location/date where available.

## Risks

- Anti-bot challenges may return 403 or an HTML interstitial. Surface this distinctly and allow disabling the source without code changes.
- JSON-LD or search markup may change. Use URL-based selectors rather than hashed CSS classes and retain `<h1>` fallback.
- Robots restrictions limit pagination. Increase keyword/city coverage rather than adding page parameters in v1.
- Salary units vary. Do not guess non-yearly salary magnitudes.
- German-language listings may be filtered by the existing language filter; this is expected, not a scrape failure.
- A serialized 1500 ms delay and max 40 detail pages can make a run slow; tune configuration if needed.

## Notes

The API is configured for port 8089 in `application.yaml`.
