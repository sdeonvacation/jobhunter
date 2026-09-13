# Requirement: wissenschaftsstellen.de Aggregator Source

## Goal

Ingest technical/software/AI job postings from `https://wissenschaftsstellen.de` — Germany's academic job board for universities, universities of applied sciences, and research institutions — into the existing ingestion → filter → scoring pipeline, scoped to the **Technik/Labor** category.

Do this by establishing a **generic university/academic-board scraping abstraction** first, with wissenschaftsstellen.de as its first concrete board. Future university boards must be thin additions (URL shapes, id extraction, selectors) rather than new bespoke strategies.

## Background

wissenschaftsstellen.de aggregates ~8,088 open positions across 448 employers (universities, HAW, research institutions, university hospitals). Its audience is academic (professorships, PhD/postdoc, teaching, administration), but the **Technik/Labor** category carries the technical staff roles relevant to this profile: Research Software Engineer, Softwareentwickler, IT/System administration, data/ML engineering, lab technicians.

Two properties of the site are material:

1. **Server-rendered HTML with inline JSON, client-side filtering** — there is no public JSON API. Only `?seite=N` is honoured server-side (50 jobs/page, ~165 pages, already newest-first). `?kat=`, `?q=`, `?fach=` are **ignored by the server** (byte-identical responses) and applied client-side by JavaScript, so category/keyword filtering must be done by the crawler after parsing. Each listing page embeds a `JOBS=[...]` array of 50 structured job objects; each job also has a detail page at `/stelle/<slug>-<numericId>` carrying the full description.
2. **Academic/technical German titles** — titles such as `Technische*r Angestellte*r`, `IT-Administrator`, `Wissenschaftliche*r Mitarbeiter*in`, `Softwareentwickler` do not match the industry-tuned role filter in `profile.yaml`, and the global `exclude-keywords` (`phd`, `promotion`, `student`, `support`, …) actively remove relevant postings. The profile's language filter also targets English JDs, whereas Technik/Labor postings are overwhelmingly German.

Both filter mismatches must be resolved **for this source only**, without changing behaviour for any other source.

## Functional Requirements

| # | Requirement | Priority |
|---|---|---|
| FR1 | Provide a **generic university-board abstraction** (abstract base + template hooks) so future academic boards are thin subclasses; shared enumeration, dedup, politeness, bounds, fault tolerance, and result mapping live in the base | Must |
| FR2 | The abstraction must support keyword-only boards (no category concept) as well as category-scoped boards, so it is not over-fit to wissenschaftsstellen | Must |
| FR3 | Onboard `wissenschaftsstellen.de` as the first concrete board on the abstraction | Must |
| FR4 | Enumerate candidate jobs by paginating the listing `?seite=N` (newest-first, 50/page) | Must |
| FR5 | Parse the embedded listing JSON (`JOBS=[...]`) rather than scraping HTML rows, and apply the Technik/Labor scope predicate in the crawler (the server ignores `?kat=`/`?q=`) | Must |
| FR6 | Fetch each in-scope job's detail page to obtain the description; map title/employer/location/apply URL from the listing JSON, with detail-page fallback | Must |
| FR7 | Derive a stable `externalId` from the listing `id`; dedup across pages | Must |
| FR8 | Support a **source-scoped role-filter override** so academic/technical title patterns replace the global include/exclude set for that source only | Must |
| FR9 | Support a **source-scoped language exemption** so German-language JDs are not skipped for that source only | Must |
| FR10 | Make overrides **YAML-configurable and reusable**: named filter profiles (e.g. `academic-university`) that board sources reference, tunable without code; inline per-source definition also allowed | Must |
| FR11 | Register `WISSENSCHAFTSSTELLEN` as a `JobSource` + `DiscoverySource` on the aggregator path (no AtsType) | Must |
| FR12 | Crawl politely: bounded pages, bounded per-run scrapes, inter-request delay; no hammering | Must |
| FR13 | Configurable via `application.yaml` aggregator source entry + `profile.yaml` profile block (no code change to toggle) | Must |
| FR14 | Observable failure states: HTTP 429 → rate-limited, 5xx/parse failure → partial-success/error, not silent | Should |

## Non-Functional Requirements

- No schema/migration/Liquibase changes — reuse the existing aggregator ingestion path (`JobPosting`, `AggregatorRun`).
- No change to endpoint-crawl (`AtsType`) filtering behaviour.
- Overrides must be **opt-in per source**; absent override = existing global behaviour, provably unchanged for all other sources.
- Filter configuration stays in `profile.yaml` (canonical filter-config home); source wiring stays in `application.yaml`.
- Respect site policy: `robots.txt` allows `User-agent: *` with `Content-Signal: search=yes,ai-train=no,use=reference`. Crawling for personal job search is permitted; content must never be fed to model training.
- Partial failures degrade gracefully (skip bad detail pages, report counts).

## Verified Site Contract (discovered 2026-09-13)

- **Listing**: `GET /?seite=N` — 50 jobs/page, ~165 pages (~8,088 jobs), ordered **newest-first by default** (descending numeric id). `?kat=`, `?q=` and `?fach=` are **ignored server-side** (verified byte-identical responses); category/keyword filtering is client-side JS (`/assets/app-kern.<date>.min.js`).
- **Embedded listing JSON**: each listing page contains a global `JOBS=[...]` array (exactly 50 objects). Keys: `id, bundesland, hochschule, kuerzel, inst_typ, kategorie, _kw, titel, frist, befristung, arbeitszeit_pct, entgeltgruppe, fachbereich_raw, tenure_track, tags, link, quelle, medi_job, _med, _kunst, _musik, _sponsored` (occasionally `_ua`). `link` is the external employer apply URL; `tags` are comma-separated keywords. **No description field.**
- **Category value**: `kategorie == "Technik/Labor"` is the scope; other values include `"Professur"`, `"Wissenschaftlicher Mitarbeiter"`, `"Doktorand/Promovierend"`, `"Postdoc"`, `"Verwaltung"`, `"Studentische Hilfskraft"`, `"Ausbildung"`, `"Abschlussarbeit/Praktikum"`, `"Bibliothek"`, `"Gesundheitsfachberufe"`, `"Sonstiges"`.
- **Detail page**: `GET /stelle/<title-slug>-<numericId>` — numeric id is stable (e.g. `42700`). Page carries the full JD description, plus breadcrumb category + Fachbereich, location (header + "Besonderheiten"), `Besoldung`/`Arbeitszeit`/`Befristung`, salary range (e.g. `2.050 – 2.976 € /Monat brutto · E 13`), application deadline, `Schlagworte`, and an external `Zur Bewerbung` link. Two `<script type="application/ld+json">` blocks are present.
- `/suche/jobs/` is **not** an endpoint (404); it only appears inside `quelle` values.
- **`robots.txt`**: `User-agent: *` → `Allow: /` with `Content-Signal: search=yes,ai-train=no,use=reference`; `CCBot` and `meta-externalagent` disallowed.
- **Sitemap**: `sitemap-stellen.xml` lists ~8k job URLs but has **no per-URL `lastmod`** — unsuitable for incremental change detection; enumeration is done via listings.

## Resolved Design Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Generic abstraction = **abstract base + thin per-board subclass** (template method), not a single config-driven DSL | Mirrors the existing `AbstractAtsStrategy` idiom; shared orchestration stays in one place, board markup stays in small testable subclasses |
| D2 | Academic filter defaults are **declared in YAML**, not hardcoded in the base | Keywords/flags are tunable without code; the abstraction supplies the mechanism, YAML supplies the policy |
| D3 | Academic overrides are **reusable named profiles in YAML** that board sources reference (inline also allowed) | Future university boards inherit the academic profile by reference instead of duplicating keyword lists |
| D4 | Scope = Technik/Labor only; role override **replaces** the global set; language **fully exempt** for the board | Approved by the user; the global `phd`/`promotion`/`student` exclusions and English-only language target would otherwise remove the relevant postings |
| D5 | Overrides surface on the source-config abstraction (like `visaExempt`) and apply to the **aggregator path only** | Guarantees zero change for endpoint crawl and all other sources |

| FR15 | Translate stored job titles to English for this source using **one batched AI call per crawl** (not per job) | Must |
| FR16 | Gate translation behind a per-source YAML flag (`translate-titles`, default off); other sources unaffected | Must |
| FR17 | Preserve the German original title in `rawContent.titleOriginal`; translate titles only (not descriptions) | Must |
| FR18 | Graceful fallback: if AI is unavailable or the call fails, store the original title; ingestion must never fail or block on translation | Must |
| FR19 | Cache translations so each unique title is translated at most once (in-memory + seeded from already-stored `titleOriginal`/`title` pairs) | Should |
| FR20 | Translate in chunks of ≤10 titles per AI call (a single batch exceeds the AI client timeout); a failing chunk falls back for its own titles only | Must |
| FR21 | Provide a backfill path (`POST /api/admin/translate-titles?source=<name>`) to translate already-stored rows that have no `titleOriginal`, so rows ingested during an AI outage are recoverable without re-ingesting | Must |

## Out of Scope

- Categories other than **Technik/Labor** (Professur, Wiss. Mitarbeiter/Doktorand, Postdoc, Lehre, Verwaltung, Med-Jobs, Pflege, Ärzte, Therapeuten).
- Sites beyond wissenschaftsstellen.de (employer ATS pages reached via the external apply URL).
- A schema/migration for the source; aggregator sources are config-declared.
- Recrawling the full sitemap on every run.

## Open / To Verify During Implementation

| # | Item | Note |
|---|---|---|
| V1 | Client-side vs server-side filtering | **RESOLVED:** `?kat=`/`?q=`/`?fach=` are ignored server-side; scope applied in the crawler from the embedded listing JSON. |
| V2 | Exact Technik/Labor volume | ~165 pages × 50 jobs; per-run bounds mandatory. |
| V3 | Detail-page field reliability | Some postings may omit location, salary, or deadline; parsing must tolerate absent fields. |
| V4 | Newest-first ordering | **RESOLVED:** the listing is newest-first by default (descending id); no sort parameter needed. |
| V5 | Detail description selector | Confirm the JD container / JSON-LD selector on a live detail page before finalizing parsing. |
