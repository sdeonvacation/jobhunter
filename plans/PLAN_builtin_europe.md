# Plan: BuiltInEurope Aggregator Source

## Overview

Add [BuiltInEurope](https://www.builtineurope.com/jobs) as a new aggregator source. Their public API (`POST https://api.builtineurope.com/search`) returns 19k+ jobs with direct ATS posting URLs. The `queries` config key is abstracted into `YamlSourceConfig` so all YAML-based aggregator strategies can leverage multi-keyword search with deduplication.

## Tech Stack

- Java 21, Spring Boot 3.3.5
- WebClient (reactive HTTP)
- Jackson (JSON parsing)
- Existing `FetchStrategy` / `YamlSourceConfig` / `DynamicSourceConfigLoader` framework

## Testing Strategy

- Unit: `BuiltInEuropeStrategyTest` — mock WebClient responses, verify pagination, dedup, keyword fan-out, field mapping
- Unit: `YamlSourceConfigTest` — verify `queries` config key parsed into `FetchContext.keywords()`
- Integration: not needed (no schema change, no new beans requiring wiring beyond component scan)
- Done when: `./gradlew test` passes, new source appears in `dynamicSources` bean with correct strategy

## Phases

### Phase 1: Abstract `queries` config into `YamlSourceConfig`

- Step 1: In `YamlSourceConfig.buildContext()`, check `extraConfig` for key `"queries"` (comma-separated string)
- Step 2: Split on `,`, trim whitespace → pass as `keywords` list to `FetchContext.forSearch()`
- Step 3: Fallback: if `queries` absent, pass `List.of()` (current behavior preserved)

### Phase 2: Add `BUILTIN_EUROPE` enum values

- Step 1: Add `BUILTIN_EUROPE` to `JobSource` enum + `AGGREGATORS` list
- Step 2: Add `BUILTIN_EUROPE` to `DiscoverySource` enum

### Phase 3: Implement `BuiltInEuropeStrategy`

- Step 1: New `@Component` class implementing `FetchStrategy`, name = `"builtineurope"`
- Step 2: For each keyword in `context.keywords()` (or `""` if empty): POST `{"query": keyword, "page": N, "per_page": 100}`
- Step 3: Paginate until empty results or `maxResults` hit
- Step 4: Deduplicate across keywords by job `id` field
- Step 5: Map response → `RawAggregatorJob` (title_raw, company_display_name, posting_url, location_name, skills as description supplement, first_seen epoch → LocalDate, salary_*)

### Phase 4: YAML config entry

- Step 1: Add source block in `application.yaml` under `aggregator.sources`
- Step 2: Config: `queries: "engineer,developer"`, `max-results: 500`, `frequency-hours: 6`

### Phase 5: Unit tests

- Step 1: `BuiltInEuropeStrategyTest` — happy path (paginated response), empty results, multi-query dedup, error handling
- Step 2: `YamlSourceConfigTest` — queries parsing (comma-split, trim, absent key)

## Risks/Edge cases

- **API rate limiting**: No auth required today; if they add throttling, `RetryableWebClientFilter` will handle transient 429s
- **`"engineer OR developer"` doesn't work**: API treats multi-word as AND — hence per-keyword fan-out with dedup
- **Overlap with existing ATS crawls**: `posting_url` points to Greenhouse/Ashby/etc — dedup pipeline already handles by `externalId`/URL
- **API schema change**: Response fields are simple — `id`, `posting_url`, `title_raw`, `company_display_name`, `location_name`, `first_seen`. Low churn risk for a Balderton-backed product.
- **Non-EU jobs in results**: API returns global jobs; existing `LocationFilter` will discard non-target locations
