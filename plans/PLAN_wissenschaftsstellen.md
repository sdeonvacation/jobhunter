# Plan: wissenschaftsstellen.de Source + Generic University-Board Abstraction

## Overview

Introduce a **generic university/academic-board scraping abstraction** in JobHunter, then onboard `wissenschaftsstellen.de` (scoped to the **Technik/Labor** category) as its first concrete board. The abstraction makes future university boards thin additions: shared enumeration, dedup, politeness, bounds, and academic-filter defaults live in one base; each board supplies only its URL shapes, id extraction, and selectors. Source-scoped filter overrides (academic role patterns + language exemption) are declared in YAML, reusable across boards and tunable without code, and leave every other source unchanged.

## Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| API | Java 21 / Spring Boot 3.3.5 | template-method base (`strategy/aggregator/university/`), mirroring `AbstractAtsStrategy` |
| Parsing | Jsoup 1.18.1 | server-rendered listing + detail HTML |
| Config | `profile.yaml` (named filter profiles + overrides), `api/src/main/resources/application.yaml` (`aggregator.sources[]`) | no DB migration, no Liquibase |
| Tests | JUnit 5 + Mockito + WireMock 3.5 | fixture-driven base-behaviour + board tests |

## Testing Strategy

- **Unit — abstraction**
  - Abstract base shared behaviour exercised through a fixture board subclass: category + keyword enumeration, id-based candidate dedup, pagination bound, per-run scrape bound, politeness delay, partial-success on mid-run failure, 429 → rate-limited, empty → empty, tolerant per-page failure (skip + continue).
  - Future-board contract: a minimal second fixture subclass that supplies only hooks proves a new board is a thin addition.
- **Unit — wissenschaftsstellen board**: listing link extraction, category-scoped keyword request URL, numeric `externalId` derivation, detail field mapping (title, employer, location, description, apply URL, salary/deadline → `rawJson`), missing-field tolerance, apply-URL fallback.
- **Unit — filter overrides**: source-scoped role set replaces global for the tagged source only; language exemption bypasses the language step for the tagged source only; named YAML profile resolves and applies; untagged sources and endpoint crawl provably unchanged.
- **Integration**: WireMock aggregator ingest with a fixture board source persists `JobPosting` rows with `source = WISSENSCHAFTSSTELLEN`, German JD not skipped, academic title kept.
- **Done when**: unit + integration green; manual `POST /api/admin/aggregate/wissenschaftsstellen` surfaces Technik/Labor software/AI jobs in the dashboard source tab; regression confirms other sources' decisions unchanged.

## Phases

### Phase 1: Generic university-board abstraction

- New abstract base (`strategy/aggregator/`) using the template-method pattern, mirroring the existing `AbstractAtsStrategy` idiom.
- **Shared (base)**: paginated listing enumeration (`?seite=N`), embedded-listing-JSON parsing hand-off, board-declared **scope predicate** applied before dedup, dedup by board id, bounded pagination and per-run scrape, **newest-first window so each run covers the freshest postings**, inter-request politeness delay, detail-fetch loop with per-page fault tolerance, and mapping of outcomes to the existing fetch-result states (success/empty/partial/rate-limited/error).
- **Board hooks (subclass)**: listing URL shape, listing JSON parsing into board-neutral records, the scope predicate, and detail field parsing.
- Base is academic-aware but not academic-hardcoded: whether a board is language-exempt and which role patterns apply come from YAML config (Phase 2), not from the class.
- Base must support boards that keep everything (scope predicate defaults to "match all") so the abstraction is not over-fit to wissenschaftsstellen.

### Phase 2: Source-scoped filter overrides + reusable academic filter profile (YAML-configurable)

- Let a source declare a **role override** (its own include/exclude pattern set that replaces the global set) and a **language exemption**; both default to absent = global behaviour.
- **YAML-configurable and reusable**: define named filter profiles in `profile.yaml` (e.g. `academic-university`) and let board sources reference them, with inline per-source definition also allowed; keywords are tunable without code and future boards inherit the academic profile by reference.
- Surface overrides on the source-config abstraction (mirroring the existing per-source `visaExempt` flag) and thread them through the **aggregator ingest path only**; endpoint crawl keeps calling the global path.
- Extend the role filter with a per-invocation override while keeping compiled global patterns as the default; handle language exemption as a chain-level skip (consistent with how `visaExempt` skips the visa step).
- Update existing filter-chain/role-filter tests; add override + named-profile-resolution coverage.

### Phase 3: wissenschaftsstellen board (first concrete board)

- Thin subclass of the Phase 1 base supplying: listing URL shape (`?seite=N`), `JOBS=[...]` embedded-JSON parsing joined with `/stelle/<slug>-<id>` anchors, the scope predicate (`kategorie == "Technik/Labor"`), and detail selectors.
- Listing JSON gives id/title/employer/location/apply-URL/category/keywords; the detail page is fetched only for in-scope jobs to obtain the **description** (the only field not in the listing JSON).
- Detail mapping: description from the JD container/JSON-LD; title/employer/location/apply URL fall back to the listing record (`link`), with the site detail URL as last resort; tolerate absent fields without dropping the job.
- Dedup across pages by listing id.

### Phase 4: Registration & configuration

- Add `WISSENSCHAFTSSTELLEN` to `JobSource` (and the aggregator list) + `DiscoverySource`.
- Add the `aggregator.sources[]` entry referencing the Phase 1 strategy, with board config (delays, bounds).
- Add the reusable academic filter profile and the board's reference to it in `profile.yaml`.
- No `AtsType`, no controller change, no Liquibase, no dashboard code change.

### Phase 5: Verification

- Run unit tests (base behaviour, board parsing, overrides) and the integration test.
- Manual crawl trigger, dashboard source-tab check, scoring spot-check.
- Regression: confirm other sources' filter outcomes unchanged.

## Risks/Edge cases

| Risk | Mitigation |
|---|---|
| Abstraction over-fit to one board | Keep hooks minimal; prove thin-add with a second fixture subclass test |
| Future board with a different scope rule | Scope is one predicate hook defaulting to "match all"; the base makes no category/keyword assumption |
| Override leaking globally | Default overrides to absent/empty; explicit regression test asserting other sources use the global set |
| Client-side-only filtering (V1, resolved) | `?kat=`/`?q=` ignored server-side; crawler applies the scope predicate from the embedded listing JSON |
| Large listing volume (V2) | Enumerate **newest-first within bounds** (bounded pages + per-run scrape); id dedup drops already-ingested jobs |
| No per-URL `lastmod` in sitemap | Enumerate via paginated listings (not sitemap); rely on id dedup + existing URL-hash dedup |
| External apply URL (employer careers page) | Keep as apply URL for fingerprint cross-match; fall back to detail URL; site detail URL retained for traceability |
| robots `ai-train=no` | Crawling permitted (`search=yes`) for personal search/reference; never feed content to model training |
| German-language JDs | Resolved: board is language-exempt via YAML profile |
| Location only on detail page | Parse from detail; tolerate header vs "Besonderheiten" mismatch and absent location |
| Detail fields partially missing | Parsing tolerates absent salary/deadline/location without dropping the job |
