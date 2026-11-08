---
description: Register a company + ATS career endpoint in the JobHunter DB via the API and verify it crawls
when_to_use: User asks to "add X endpoint to db", "add <company> careers page", or "crawl <company> jobs" where X is a company ATS board (Greenhouse, Lever, SuccessFactors, ...)
---

## Context

Registering an endpoint looks like one POST, but three silent failure modes bite agents:
1. The MCP `add_company` tool (`mcp-server/src/tools/addCompany.ts`) sends only `{name, careersUrl}` — no `atsType` → endpoint stored as `UNKNOWN` → `CrawlService.crawlEndpoint` finds no strategy (`StrategyRegistry.getStrategy(UNKNOWN)` is empty) → status `SKIPPED`, 0 jobs, no error.
2. `POST /api/companies` returns `CompanySummaryDto` which has **no endpoint id** (and `endpointCount` can read 0 — `DtoMapper.java:139` swallows lazy-load failures). You must `GET /api/companies/{id}` to get the endpoint id for crawling.
3. The endpoint URL must match what the ATS strategy's URL builder expects, or the crawl 404s/returns empty forever.

## Steps

### 1. Ensure API is up

```bash
PORT=$(cat /tmp/jobhunter-api.port 2>/dev/null || echo 8089)
curl -s "http://localhost:$PORT/api/admin/health" >/dev/null || (make status && make dev)
```

### 2. Derive `atsType` from the URL

Check `api/src/main/java/dev/jobhunter/resolution/AtsDetector.java` `URL_PATTERNS` (greenhouse, `jobs.eu.lever.co`→**LEVER_EU**, `jobs.lever.co`→LEVER, ashby, workday, stepstone, pinpoint, personio).

- **Not in that list** (SuccessFactors, SmartRecruiters, Workable, ...): detect by probing the page (see step 4) or from the job board you fetched the JD from; set the enum explicitly.
- Full enum: `api/src/main/java/dev/jobhunter/model/enums/AtsType.java`.
- **LEVER vs LEVER_EU**: `jobs.eu.lever.co/...` must be `LEVER_EU` — `LeverStrategy` hits `api.eu.lever.co` only for that type; wrong type → 404 → `ERROR`, `consecutiveErrors++`.

### 3. Check for an existing company (dedup)

```bash
curl -s "http://localhost:$PORT/api/companies?search=Sonova" | jq '.content[] | {id, name, status, endpointCount}'
```

- `POST /api/companies` dedups by **normalized name** (`CompanyService.java:42`): lowercased, legal suffixes stripped (`gmbh|se|ag|inc|ltd|corp|llc|co|plc|sarl|srl|bv|nv`). "Sonova GmbH" == "Sonova", but **"Sonova Group" is a different row** — JOBSPY/discovery often created near-duplicates; pick the right one.
- `search` is `LIKE %x%` (case-sensitive on Postgres) — try both casings if empty.
- Existing company → POST just attaches the endpoint to it; no duplicate company is created.

### 4. Validate URL shape against the strategy BEFORE registering

**SuccessFactors** (`SuccessFactorsStrategy.buildSearchUrl`, line 527): the strategy reuses `q`, `locationsearch`, `locale` from the endpoint URL's query string and appends `startrow`. Endpoint must be the search page with those params:

```
https://jobs.sonova.com/search/?q=&locationsearch=Germany&locale=en_US
```

Probe it first — HTTP 200 and the parser's aria pattern must match (`ARIA_TOTAL_PATTERN`, `SuccessFactorsStrategy.java:43`):

```bash
curl -s "$CAREERS_URL" | grep -oE 'Results [0-9]+ to [0-9]+ of [0-9]+' | head -1
# expect: Results 1 to 25 of 32
```

**Lever**: URL must be `https://jobs.eu.lever.co/<slug>/` — slug is extracted server-side by `CompanyController.extractSlugFromUrl` (`SLUG_PATTERNS`, line 163) into `ats_slug`, which `LeverStrategy` formats into `api.eu.lever.co/v0/postings/%s`. A URL not matching any pattern → `atsSlug=null` → request to `.../postings/null` → 404.

**SuccessFactors slug**: no pattern matches → `atsSlug=null` is fine (SF uses origin, not slug).

### 5. Register — pass `atsType` explicitly (never use MCP `add_company`)

```bash
curl -s -X POST "http://localhost:$PORT/api/companies" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Sonova",
    "careersUrl": "https://jobs.sonova.com/search/?q=&locationsearch=Germany&locale=en_US",
    "atsType": "SUCCESSFACTORS"
  }' | jq '{id, name, status, endpointCount}'
```

Body record: `AddCompanyRequest(name, domain, country, careersUrl, atsType)` (`CompanyController.java:159`). `domain`/`country` optional but useful. `atsType` omitted → `UNKNOWN` (trap #1).

Creates/reuses company (status `DISCOVERED`) + endpoint (`source="mcp"`, `confidence=MEDIUM`, `isActive=true`) unless a case-insensitive identical `careersUrl` already exists for that company (idempotent).

### 6. Get the endpoint id (mandatory — POST doesn't return it)

```bash
curl -s "http://localhost:$PORT/api/companies/$COMPANY_ID" | jq '.endpoints[] | {id, url, atsType, isActive, lastCrawlStatus}'
```

If `endpoints` is empty despite passing `careersUrl`, re-check the URL wasn't blank and retry — do NOT trust `endpointCount` from the POST response (trap #2).

### 7. Crawl and interpret the result

```bash
curl -s -X POST "http://localhost:$PORT/api/admin/crawl/$ENDPOINT_ID"
# → {"endpointId":"...","jobsFound":3}
```

`jobsFound` = **new jobs only** (`CrawlService.crawlEndpoint` returns `newJobsCount`), not total found.

### 8. Verify

```bash
curl -s "http://localhost:$PORT/api/companies/$COMPANY_ID" | jq '.endpoints[] | {lastCrawlStatus, lastCrawledAt, isActive}'
curl -s "http://localhost:$PORT/api/jobs?company=Sonova&size=50" | jq '.content[] | {title, location}'
```

Success criteria: `lastCrawlStatus = SUCCESS`, `jobsFound > 0`, target job visible.

## Gotchas

- **`SKIPPED` = wrong/missing `atsType`** (no registered strategy). Only fix is updating the endpoint's `ats_type` — there is no PATCH endpoint, so re-register correctly or fix in DB.
- **`jobsFound: 0` needs disambiguation** via `lastCrawlStatus`:
  - `SKIPPED` → bad atsType.
  - `EMPTY` → URL shape wrong or board truly empty (also resets `consecutiveErrors`).
  - `ERROR` → `consecutiveErrors++`; at 10 the endpoint auto-deactivates (`CrawlService.java:183`) → later needs `POST /api/admin/endpoints/{id}/reactivate`.
  - `SUCCESS` + 0 → all jobs already existed (expected on re-crawl).
- **`/api/jobs` only lists `FilterDecision.KEEP`** + active + not-applied jobs. A job that was crawled but filtered (location/language) exists in DB but won't appear — don't conclude the crawl failed.
- **`PENDING_DETECTION`** on the company means discovery created it without a resolved endpoint; your explicit POST with `careersUrl` still attaches one, and the endpoint crawls regardless of company status (`findAllActiveNonCustom` filters only `isActive` + `atsType != CUSTOM`).
- **Do not register duplicate companies** — search first (step 3). Two rows ("Sonova", "Sonova Group") fragment jobs across companies and break the `company=` jobs filter.
- **Re-POST is safe**: identical URL (case-insensitive) on the same company creates nothing; use it only to fix a wrong `atsType` by first removing the bad row.
- DB-seed alternative (needs rebuild/restart, use only for records that must ship with the schema): Liquibase pattern in `api/src/main/resources/db/changelog/021-seed-kraken.sql` — `INSERT ... SELECT ... WHERE NOT EXISTS` guard + `PENDING_DETECTION → ACTIVE` status flip.

## Templates

### Full sequence (copy-adapt)

```bash
PORT=$(cat /tmp/jobhunter-api.port 2>/dev/null || echo 8089)
API="http://localhost:$PORT"

# 1. dedup check
curl -s "$API/companies?search=Unzer" | jq '.content[] | {id, name, status, endpointCount}'

# 2. probe URL shape (SuccessFactors aria pattern; adjust per ATS)
curl -s "https://jobs.eu.lever.co/unzer/" -o /dev/null -w '%{http_code}\n'

# 3. register with explicit atsType
COMPANY_ID=$(curl -s -X POST "$API/companies" -H 'Content-Type: application/json' \
  -d '{"name":"Unzer","careersUrl":"https://jobs.eu.lever.co/unzer/","atsType":"LEVER_EU"}' | jq -r '.id')

# 4. fetch endpoint id
ENDPOINT_ID=$(curl -s "$API/companies/$COMPANY_ID" | jq -r '.endpoints[0].id')

# 5. crawl + verify
curl -s -X POST "$API/admin/crawl/$ENDPOINT_ID"
curl -s "$API/companies/$COMPANY_ID" | jq '.endpoints[] | {atsType, lastCrawlStatus}'
curl -s "$API/jobs?company=Unzer&size=20" | jq '.content[] | {title, location}'
```
