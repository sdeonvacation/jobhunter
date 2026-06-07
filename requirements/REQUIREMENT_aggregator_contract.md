# Requirement: Unified Aggregator Contract

## Problem Statement

LinkedIn, Indeed, Berlin Startup Jobs, and Arbeitnow are all job aggregators — they surface jobs from many employers. Currently each is handled with a completely different pattern:

- **LinkedIn**: Dedicated package, MCP client, has company matching logic
- **Indeed**: Dedicated package, CLI-based, has company matching logic
- **BSJ**: Shoehorned as a "Company" with `AtsType.CUSTOM`, no company resolution
- **Arbeitnow**: AtsType enum exists, no implementation at all

The duplicated ingestion logic (company resolution, filtering, persistence) should be unified under a common contract.

## Requirements

### Functional

1. Define a common `AggregatorSource` interface that all aggregator implementations conform to
2. Each aggregator extracts jobs **with the employer company name** (not the aggregator name)
3. A shared ingestion service handles:
   - Company resolution (match existing or create new)
   - Filter chain application (role, location, language, YOE, dedup)
   - Job persistence under the real employer's Company entity
   - Source/origin tracking on JobPosting
4. Each aggregator keeps its own transport/fetch mechanism:
   - LinkedIn: MCP client
   - Indeed: jobspy-js CLI
   - BSJ: HTML scrape + AI extraction
   - Arbeitnow: REST API
5. Jobs from aggregators must be distinguishable from ATS-crawled jobs (source field)
6. Deduplication must work across sources (same job found on LinkedIn AND company's Greenhouse page)

### Non-Functional

- Adding a new aggregator should require only implementing the fetch interface, not duplicating ingestion logic
- Existing LinkedIn and Indeed functionality must not regress
- Aggregator crawl scheduling should reuse the existing Quartz infrastructure

## Out of Scope

- Company discovery/enrichment from aggregator data (Option A from earlier discussion)
- Changing the dashboard UI for aggregator jobs
- Removing the existing LinkedIn/Indeed packages (refactor, not rewrite)

## Aggregator Sources

| Source | Transport | Data format | Has public API |
|--------|-----------|-------------|---------------|
| LinkedIn | HTTP MCP client | JSON (structured content) | No (browser automation) |
| Indeed | jobspy-js CLI subprocess | JSON file output | No (scraping) |
| Berlin Startup Jobs | HTML fetch + AI | HTML → AI-parsed JSON | No |
| Arbeitnow | HTTP REST | JSON (documented API) | Yes |
