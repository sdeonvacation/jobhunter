# Requirement: WorkInFinland Aggregator Source

## Goal

Add Work in Finland (`https://www.workinfinland.com/en/open-jobs/`) as a new aggregator job source in JobHunter, feeding Finnish job postings into the existing ingestion → filter → scoring pipeline.

## Background

Work in Finland is the official Talent Boost / Business Finland portal for international professionals seeking jobs in Finland. It is a meta-aggregator: it collects postings from multiple Finnish boards (Jobly, Työmarkkinatori / TE Services, TheHub, and others) and exposes them through a public, unauthenticated JSON API.

## Functional Requirements

| # | Requirement | Priority |
|---|---|---|
| FR1 | Discover and fetch Finnish job postings from the WorkInFinland public jobs API | Must |
| FR2 | Map each posting to the existing `RawAggregatorJob` contract and persist via `AggregatorIngestionServiceImpl` | Must |
| FR3 | Support pagination across the full result set, capped by a configurable `max-results` | Must |
| FR4 | Support optional category filtering (tech-relevant categories) via YAML config | Should |
| FR5 | Derive a stable `externalId` for deduplication (list API exposes no `id` field) | Must |
| FR6 | Let missing descriptions be filled by the existing `AggregatorDescriptionEnricher` (fetch `externalUrl`) | Must |
| FR7 | Mark the source `visa-exempt` (targets international candidates) | Must |
| FR8 | Register as a `JobSource` and `DiscoverySource` so stats, dedup, and company discovery work | Must |
| FR9 | Configurable via `application.yaml` `aggregator.sources` (no code change to toggle) | Must |

## Non-Functional Requirements

- No schema/migration changes.
- No new Spring infrastructure; reuse `FetchStrategy`, `StrategyRegistry`, `SourceConfig`, `DynamicSourceConfigLoader`.
- Follow existing aggregator-strategy conventions (`JobgetherStrategy`, `BuiltInEuropeStrategy`, `InstaffoStrategy`).
- Respect the source: bounded page size, configurable delay, stop on `max-results` / `totalPages`.
- Graceful degradation: rate limit / 4xx → `FetchResult.rateLimited()` / `FetchResult.error()`; partial results retained where possible.

## Verified API Contract (discovered 2026-08-26)

- Endpoint: `GET https://www.workinfinland.com/api/jobs/` — public, no auth/API key.
- Query params: `limit` (int), `page` (1-based int), `category` (slug), `city` (slug).
- Response envelope: `{ totalJobs, totalPages, categories[], cities[], jobs[] }`.
- Job object: `{ title, employer: { name, city, imageUrl }, expireDate, externalUrl }`.
- **No** `id`, **no** `description`, **no** posted date, **no** salary in the list response.
- `externalUrl` points at the source board (jobly.fi, tyomarkkinatori.fi, thehub.fi, ...) and serves as both stable key and apply URL.
- Observed counts (unfiltered): `totalJobs` ≈ 1092; tech-relevant category slugs include `software-development` (~209), `ict` (~453), `deep-tech` (~284), `engineering` (~399).

## Out of Scope

- Scraping individual source-board pages inside the strategy (delegated to the existing description-enrichment pipeline).
- Työmarkkinatori P67 / retrieval API (requires onboarding + credentials) — not needed since the WorkInFinland JSON API is public.
- Modifying the location filter — Finland (`FI`) is already a target country in `CityCountryResolver` via `filters.visa-sponsorship.target-countries`.
