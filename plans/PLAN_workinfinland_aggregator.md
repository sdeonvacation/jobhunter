# Plan: WorkInFinland Aggregator Source

## Overview

Integrate Work in Finland (Business Finland's Talent Boost portal for international talent) as a new aggregator source. WorkInFinland exposes a public, unauthenticated JSON API (`GET /api/jobs/`) that lists ~1092 Finnish postings aggregated from Jobly, Työmarkkinatori/TE Services, TheHub and others. The strategy pages this API, maps each posting to `RawAggregatorJob`, and relies on the existing ingestion → filter → scoring pipeline plus the description-enrichment backfill for the missing descriptions.

## Tech Stack

- Java 21, Spring Boot 3.3.5
- `WebClient` for HTTP, Jackson `ObjectMapper` for JSON parsing
- Existing `FetchStrategy` interface + `AggregatorIngestionServiceImpl` + `DynamicSourceConfigLoader` pipeline
- No new dependencies, no schema changes

## Testing Strategy

- Unit: `WorkInFinlandStrategyTest` (JUnit 5 + WireMock) — pagination, category fan-out + dedup, field mapping, `max-results` cap, error/rate-limit/empty handling, externalId derivation.
- Integration: not required (no schema/new beans); `DynamicSourceConfigLoaderTest` pattern validates wiring.
- Done when: `./gradlew test --tests "dev.jobhunter.strategy.aggregator.WorkInFinlandStrategyTest"` passes; manual crawl returns >0 jobs for source `WORK_IN_FINLAND`; jobs have non-null `applyUrl`/`externalId` and are later description-enriched.

## Phases

### Phase 1: Enums & Configuration

- Step 1: Add `WORK_IN_FINLAND` to `JobSource` enum and its `AGGREGATORS` list.
- Step 2: Add `WORK_IN_FINLAND` to `DiscoverySource` enum.
- Step 3: Add YAML entry under `aggregator.sources` in `application.yaml` (strategy `workinfinland`, `visa-exempt: true`, category + limit config).

### Phase 2: WorkInFinlandStrategy Implementation

- Step 1: Create `WorkInFinlandStrategy` (`@Component`, implements `FetchStrategy`, `name() = "workinfinland"`).
- Step 2: Implement fetch of `GET /api/jobs/` with `limit` + `page`; paginate until `totalPages` or `max-results` reached.
- Step 3: Implement optional category fan-out — for each `categories` config slug, fetch and dedup by `externalUrl` (mirrors BuiltInEurope keyword fan-out). Fetch all when no categories configured.
- Step 4: Map each job: `externalId` ← `externalUrl` (stable unique key), `title` ← `title`, `companyName` ← `employer.name`, `location` ← `employer.city`, `applyUrl` ← `externalUrl`, `description` ← null (enriched later), `postedDate` ← null, salary ← null, `rawJson` ← node.
- Step 5: Handle 429 → `FetchResult.rateLimited()`, 4xx/5xx → `FetchResult.error()`, malformed JSON → `FetchResult.error()`, empty → `FetchResult.empty()`. Retain partial results on late-page failure.

### Phase 3: Registration & Validation

- Step 1: Confirm `StrategyRegistry` resolves `"workinfinland"` via component scan + `DynamicSourceConfigLoader` picks up the source.
- Step 2: Manual trigger + verify jobs persist under source `WORK_IN_FINLAND`.
- Step 3: Verify filter chain (role/location/YOE) and description enrichment run correctly on ingested jobs.

## Risks/Edge cases

- **No description in list response**: description is null at ingestion; filled later by `AggregatorDescriptionEnricher` which fetches `externalUrl`. Some source boards (jobly.fi) are JS-rendered, so enrichment may be partial. Mitigation: accept partial enrichment (existing behavior); monitor enrichment success rate.
- **No stable `id` field**: derive `externalId` from `externalUrl` (unique, stable). Use a SHA-256 hash of the URL if > 255 chars (VARCHAR default). Mitigation: document derivation + test.
- **`limit` upper bound unknown**: frontend uses 12; larger page size reduces request count. Mitigation: verify max `limit` at implementation time; fall back to a smaller page size (e.g. 50) if large values are rejected/truncated.
- **Category slugs may change or overlap**: jobs appear under multiple categories (e.g. `ict` and `software-development`). Mitigation: dedup by `externalUrl` across categories; category slugs configurable in YAML.
- **`expireDate` is a liveness signal, not a post date**: leave `postedDate` null (semantically different); optionally retain `expireDate` in `rawJson`.
- **Location filter**: Finland (`FI`) is already a target country via `filters.visa-sponsorship.target-countries` (includes `finland`, `fi`, `helsinki`), so city-only locations (e.g. "Espoo") resolve to `FI` and are kept. No profile change required.
- **Cross-source duplicates**: same posting may exist on Jobly/Työmarkkinatori/TheHub and be tracked via other sources. Mitigation: existing fingerprint dedup (`title+company+location`) in `AggregatorIngestionServiceImpl`.
- **Rate limiting / anti-bot**: public API is unauthenticated but may throttle. Mitigation: configurable delay between pages, respect 429, stop early and return partial results.
