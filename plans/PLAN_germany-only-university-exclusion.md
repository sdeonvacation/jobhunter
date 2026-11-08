# Plan: Germany-only crawl/digest + stop university jobs

## Overview

Restrict the entire JobHunter pipeline (crawl, discovery, digest) to Germany-located jobs plus remote jobs, and stop crawling academic/research positions entirely. This touches the location filter (authoritative target-country set), the discovery provider locations, the non-German aggregator sources, the wissenschaftsstellen university board, university/research career endpoints, and the digest query. Remote jobs are retained; all other country-located jobs are dropped. Existing data is left in place and ages out naturally (forward-only).

## Tech Stack

- Java 21 / Spring Boot 3.3.5 (`api/`)
- Config: `profile.yaml` (filters), `api/src/main/resources/application.yaml` (sources, discovery)
- PostgreSQL 16 (runtime data: `career_endpoints`)
- JUnit 5 + WireMock (unit/integration)

## Testing Strategy

- Unit: `LocationFilterImpl`/`CityCountryResolver` already cover target-country resolution — verify DE-only still KEEPs `REMOTE_EU`. Add `DiscoveryService` test: a provider returning an academic company (e.g. "Max-Planck-Institut") is NOT registered and no endpoint is added.
- Integration: repository test for the digest query's new country predicate (DE/REMOTE_EU returned; NL excluded).
- Done when:
  - `profile.yaml` target countries resolve to DE-only (plus remote)
  - discovery provider locations are Germany-only
  - non-German aggregator sources + `wissenschaftsstellen` are disabled
  - academic `career_endpoints` rows are deactivated
  - discovery skips academic company names
  - digest query returns only `locationCountry IN ('DE','REMOTE_EU')`

## Phases

### Phase 1: Germany-only target countries (config)

- Step 1: Reduce `profile.yaml` → `filters.visa-sponsorship.target-countries` to Germany only (`["germany", "deutschland", "\\bde\\b"]`). `CityCountryResolver` hard-adds `DE` anyway (`CityCountryResolver:147`), so this drives `targetIsos` = {DE} and the location filter SKIPs every other resolved country. Remote jobs are unaffected: `LocationFilterImpl` checks the remote pattern before target-country resolution.
- Step 2: Set `preferences.locations` to `["Germany", "remote"]` (drop `Switzerland`) for config consistency.

### Phase 2: Scope discovery + aggregator sources to Germany (application.yaml)

- Step 1: `discovery.providers.jobspy.locations` → `["Germany"]` (currently 19 EU countries + remote).
- Step 2: `discovery.providers.linkedin.locations` → `["Germany"]` (currently 29 countries). This also shrinks the scarce LinkedIn SEARCH-token crawl window, which is safe.
- Step 3: Disable non-German aggregator sources (`enabled: false`): `workinfinland`, `careersingothenburg`, `visajobs`, `globalmove`. Keep Germany-scoped sources: `berlinstartupjobs`, `arbeitnow`, `instaffo`, `stepstone`, `jobgether-*` (already `/germany/` URLs). `builtineurope` is Europe-wide — either disable or leave for the location filter to SKIP; decide in Phase 2 review.
- Note: the location filter is the correctness net; source disabling is crawl-volume / token optimization.

### Phase 3: Stop crawling university/academic jobs

- Step 1: Disable the `wissenschaftsstellen` aggregator source (`enabled: false`).
- Step 2: Remove now-unused `profile.yaml` `source-filter-overrides.wissenschaftsstellen` and `filter-profiles.academic-university` (cleanup; the `academic-university` profile has no other consumer).
- Step 3: Deactivate academic/research `career_endpoints` rows (Fraunhofer, Max Planck, Helmholtz, Leibniz, `TU*`, `Universität`/`Hochschule`, RWTH, ETH, etc.) via a direct dev-DB `UPDATE` on `career_endpoints` matched by company name — runtime data, NOT a Liquibase migration (per project rule). Identify candidates with a name-keyword query first, review, then set `is_active=false`.
- Step 4: Block discovery from re-adding academic institutions. Add `discovery.academic-blocklist` (regex list) to `application.yaml`, read via `DiscoveryProperties`, and check it against `companyNormalizer.normalize(...)` / company name in `DiscoveryService` before `registerCompany` and `tryAddNewEndpoint` (skip + log + record `DiscoveryOutcome` as skipped).

### Phase 4: Digest Germany-only guarantee (defense-in-depth)

- Step 1: Extend `JobPostingRepository.findDigestJobsSince` (and, for consistency, `findRecentlyPostedJobs` used by `/radar`) with a country predicate `AND j.locationCountry IN ('DE','REMOTE_EU')`. This closes the re-post window (`postedDate >= sinceDate`) that would otherwise re-surface older non-German KEEP jobs.
- Step 2: Verify the stored `location_country` format during build (pipeline writes `chainResult.countryIso()` = ISO2 `DE` / literal `REMOTE_EU`); confirm no full-name values before finalizing the predicate.

## Risks/Edge cases

- **Remote jobs kept**: user chose to retain `REMOTE_EU`, so location-agnostic postings (including non-German employers) still surface — accepted.
- **Null `locationCountry` on historical rows**: excluded by the digest predicate; forward-only re-crawl refreshes them. Verify no `'Germany'`-style full-name values linger.
- **Academic blocklist false positives**: conservative matching (word-boundary academic tokens) to avoid blocking real companies (e.g. "Uni X" private firms).
- **`builtineurope` scope**: Europe-wide source; either disable or rely on the location filter — resolve during implementation.
- **LinkedIn token budget**: reducing discovery locations to Germany shrinks the keyword×location pair space; no regression expected.
