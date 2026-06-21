# HLD: Visa Sponsorship Detection for EU Jobs

## Tech Stack

| Category  | Technology        | Purpose                                              |
| --------- | ----------------- | ---------------------------------------------------- |
| Language  | Java 21           | Project standard, records for detection results      |
| Framework | Spring Boot 3.3.5 | DI, constructor injection, component scan            |
| Database  | PostgreSQL 16     | Persistence, VARCHAR enum column for visa status     |
| Migration | Liquibase         | Schema evolution (add `visa_sponsorship` column)     |
| Config    | profile.yaml      | Detection patterns, target countries, thresholds     |
| AI        | Anthropic/OpenAI  | Optional fallback classification (existing infra)    |
| Frontend  | React 18 + Tailwind | Visa badge component on JobCard                   |

## Components

| Component                | Responsibility                                                  | Dependencies                                     |
| ------------------------ | --------------------------------------------------------------- | ------------------------------------------------ |
| VisaDetectionStrategy    | Interface: detect visa signals from job description             | None (contract only)                             |
| RegexVisaDetectionStrategy | Regex-based detection using config patterns                   | PersonalProfileLoader, compiled Pattern list     |
| AiVisaDetectionStrategy  | AI classification fallback (gated by config flag)               | AI provider infra, PersonalProfileLoader         |
| VisaDetectionChain       | Orchestrates strategies in order: regex → AI fallback           | List\<VisaDetectionStrategy\>, config flag       |
| VisaSponsorshipFilter    | Conditional filter: bypass DE, require visa signal for EU       | VisaDetectionChain, PersonalProfileLoader        |
| VisaSponsorship (enum)   | Detection outcome states                                        | None                                             |
| VisaSponsorshipConfig    | Config record in PersonalProfile                                | PersonalProfileLoader                            |
| VisaBadge (React)        | Dashboard badge component for visa-confirmed jobs               | Job type                                         |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Ingestion Pipeline                                 │
│   CrawlService  /  AggregatorIngestionServiceImpl                    │
└──────┬────────────────────────────────────────────────────┬──────────┘
       │                                                    │
       ▼                                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│               Shared Filter Chain                                      │
│  Language → Role → Location → [VisaSponsorshipFilter] → YOE → Dedup  │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │    VisaSponsorshipFilter      │
                    │                              │
                    │  if DE country → KEEP (skip) │
                    │  if non-DE EU → run chain    │
                    │  if remote-EU → run chain    │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │      VisaDetectionChain       │
                    │                              │
                    │  1. RegexVisaDetection        │
                    │     → CONFIRMED/REJECTED?     │
                    │       return immediately      │
                    │     → UNCLEAR?                │
                    │  2. AiVisaDetection (if on)   │
                    │     → CONFIRMED/REJECTED/     │
                    │       UNKNOWN                 │
                    │  3. fallback: UNKNOWN         │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │  FilterResult decision:       │
                    │  CONFIRMED/LIKELY → KEEP     │
                    │  REJECTED/UNKNOWN → SKIP     │
                    └──────────────────────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │  job.setVisaSponsorship(X)    │
                    │  before persist               │
                    └──────────────────────────────┘
```

**Description**: The `VisaSponsorshipFilter` inserts into the existing filter chain AFTER `LocationFilter` and BEFORE `YoeFilter`. LocationFilter is widened to accept target EU countries (via config). For DE jobs, visa filter is a no-op (returns KEEP immediately). For non-DE EU jobs that pass location filter, the `VisaDetectionChain` runs regex first, then optionally AI. The detection result maps to a FilterDecision (CONFIRMED/LIKELY→KEEP, REJECTED/UNKNOWN→SKIP) and is persisted on the entity regardless of filter outcome.

## Interfaces

### VisaDetectionStrategy

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `detect` | `String description` | `VisaDetectionResult` | Analyze description for visa signals | Returns UNKNOWN on null/empty input |

### VisaDetectionResult (record)

| Field | Type | Description |
|-------|------|-------------|
| `status` | `VisaSponsorship` | CONFIRMED, LIKELY, REJECTED, UNKNOWN |
| `confidence` | `double` | 0.0–1.0 confidence in determination |
| `reason` | `String` | Human-readable explanation (e.g., "matched: visa sponsorship available") |

### RegexVisaDetectionStrategy

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `detect` | `String description` | `VisaDetectionResult` | Match positive patterns, check negative overrides. Negative wins on conflict. Returns CONFIRMED (positive match, no negative), REJECTED (negative match), UNCLEAR (no match) | Returns UNKNOWN on null/blank |

### AiVisaDetectionStrategy

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `detect` | `String description` | `VisaDetectionResult` | Send truncated description to AI for classification. System prompt asks: "Does this job offer visa sponsorship?" Expected AI response: yes/no/unclear | Returns UNKNOWN on AI error (fail-open). Logs warning. |

### VisaDetectionChain

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `evaluate` | `String description` | `VisaDetectionResult` | Run regex first. If CONFIRMED/REJECTED → return. If UNCLEAR and AI enabled → run AI. Else return UNKNOWN | Never throws; wraps AI errors as UNKNOWN |

### VisaSponsorshipFilter

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| `filter` | `String locationCountry, String location, String description, boolean isAggregator` | `VisaFilterResult` | 1) If country in DE-patterns → KEEP (bypass). 2) If country in target-EU-countries OR location matches remote-EU patterns: a) if isAggregator=true → return KEEP with PENDING (defer to enrichment pass). b) if isAggregator=false → run detection chain immediately. 3) Map result to FilterDecision. | Returns KEEP on null country (existing behavior preserved) |

### VisaFilterResult (record)

| Field | Type | Description |
|-------|------|-------------|
| `decision` | `FilterDecision` | KEEP or SKIP |
| `reason` | `String` | Filter reason (null for KEEP) |
| `visaSponsorship` | `VisaSponsorship` | Detection result to persist on entity |

## Data Flow

### Happy Path: EU Job with Visa Signal

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | CrawlService / AggregatorIngestionService | Receives raw job from extractor/aggregator | Apply filter chain |
| 2 | LanguageFilter | Check English content | Pass → RoleFilter |
| 3 | RoleRelevanceFilter | Check title matches engineering roles | Pass → LocationFilter |
| 4 | LocationFilter (widened) | Match location against DE cities + EU target countries + remote-EU | Pass (NL match) → VisaSponsorshipFilter |
| 5 | VisaSponsorshipFilter | Detect country = "Netherlands" (non-DE EU) → invoke chain | VisaDetectionChain |
| 6 | RegexVisaDetectionStrategy | Scan description for positive patterns → finds "visa sponsorship provided" | Return CONFIRMED (0.95) |
| 7 | VisaSponsorshipFilter | CONFIRMED → FilterDecision.KEEP | Return VisaFilterResult(KEEP, null, CONFIRMED) |
| 8 | YoeFilter | Check years of experience | Pass → Dedup |
| 9 | DeduplicationFilter | Check fingerprint | Pass |
| 10 | CrawlService | `job.setVisaSponsorship(CONFIRMED)` + persist | JobPostingRepository.save() |

### DE Job (Bypass Path)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1–4 | Filter chain | Location matches "Berlin" → passes LocationFilter | VisaSponsorshipFilter |
| 5 | VisaSponsorshipFilter | Country = "Germany" → matches DE patterns → bypass | Return VisaFilterResult(KEEP, null, null) |
| 6+ | Remaining filters | Continue as before (no visa field set) | Persist |

### EU Job without Visa Signal — Direct Endpoint (Rejected)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1–4 | Filter chain | Location matches "Amsterdam" → passes LocationFilter | VisaSponsorshipFilter |
| 5 | VisaSponsorshipFilter | Country = "Netherlands" → invoke chain | VisaDetectionChain |
| 6 | RegexVisaDetectionStrategy | No positive/negative pattern matches → UNCLEAR | Return to chain |
| 7 | VisaDetectionChain | AI disabled (default) → return UNKNOWN | VisaSponsorshipFilter |
| 8 | VisaSponsorshipFilter | UNKNOWN + config `unknown-action: skip` → SKIP | Return VisaFilterResult(SKIP, "visa: no sponsorship signal", UNKNOWN) |
| 9 | CrawlService | `job.setVisaSponsorship(UNKNOWN)`, `filterReason = "visa: ..."` + persist with SKIP | JobPostingRepository.save() |

### EU Job from Aggregator — Two-Pass Flow

Aggregator sources (BSJ, Arbeitnow, LinkedIn) provide only a 200-char description stub at ingestion time. The full description lives at `applyUrl`. Visa regex cannot reliably detect signals in 200 chars. Solution: two-pass evaluation.

**Pass 1 — Ingestion (store with PENDING)**:

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | AggregatorIngestionServiceImpl | Receives raw job with 200-char stub | Apply filter chain |
| 2–4 | Language → Role → Location | Standard filters pass (EU country in target list) | VisaSponsorshipFilter |
| 5 | VisaSponsorshipFilter | Detects aggregator source + non-DE EU → skip visa detection, return KEEP with `PENDING` | Return VisaFilterResult(KEEP, null, PENDING) |
| 6+ | YOE → Dedup | Standard filters | Persist |
| 7 | AggregatorIngestionServiceImpl | `job.setVisaSponsorship(PENDING)` + persist | Stored, awaiting enrichment |

**Pass 2 — Post-Enrichment Re-evaluation**:

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | AggregatorDescriptionEnricher | Fetches full description from `applyUrl` for jobs with short descriptions | Updates `job.description` |
| 2 | AggregatorDescriptionEnricher | Detects `job.visaSponsorship == PENDING` → invoke `VisaDetectionChain` on full description | VisaDetectionChain |
| 3 | VisaDetectionChain | Regex (and optionally AI) evaluates full description | Returns result |
| 4 | AggregatorDescriptionEnricher | CONFIRMED/LIKELY → update visa field, keep job active | `job.setVisaSponsorship(CONFIRMED)` |
| 5 | AggregatorDescriptionEnricher | REJECTED/UNKNOWN → deactivate job | `job.setIsActive(false)`, `job.setFilterReason("visa: no sponsorship signal")` |

**Key invariants**:
- DE aggregator jobs: never get PENDING (VisaSponsorshipFilter bypasses for DE). Enricher ignores them for visa re-evaluation.
- Only non-DE EU aggregator jobs get PENDING state.
- PENDING jobs are invisible in dashboard queries (query should filter `visa_sponsorship != 'PENDING'` OR `visa_sponsorship IS NULL` for DE jobs).
- If enricher fails to fetch `applyUrl` (HTTP error), visa field stays PENDING → job remains hidden until next enrichment attempt.

**Error Flows**:
- AI provider timeout/error → `AiVisaDetectionStrategy` catches exception, logs warning, returns `VisaDetectionResult(UNKNOWN, 0.0, "AI error: <message>")`. Chain continues with UNKNOWN.
- Null description → `RegexVisaDetectionStrategy.detect()` returns UNKNOWN immediately. No patterns evaluated.
- Missing config section → `VisaSponsorshipFilter` is effectively disabled (empty target-countries list means no non-DE jobs reach detection chain). Filter returns KEEP with null visa status.
- Enricher HTTP failure → job stays PENDING (not visible in dashboard). Retried on next enrichment cycle.

## Data Model

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| JobPosting (extended) | `visaSponsorship: VisaSponsorship` (enum: CONFIRMED, LIKELY, PENDING, REJECTED, UNKNOWN) | Existing Company, MatchScore, OpportunityScore | Nullable (null = not evaluated, i.e., DE jobs) |

### Liquibase Migration: `008-visa-sponsorship.sql`

```sql
--liquibase formatted sql
--changeset jobhunter:008-visa-sponsorship

ALTER TABLE job_posting ADD COLUMN visa_sponsorship VARCHAR(20);

COMMENT ON COLUMN job_posting.visa_sponsorship IS 'Visa sponsorship detection result: CONFIRMED, LIKELY, REJECTED, UNKNOWN. NULL = not evaluated (DE jobs).';
```

### VisaSponsorship Enum

```java
package dev.jobhunter.model.enums;

public enum VisaSponsorship {
    CONFIRMED,   // Positive regex or AI confirmation
    LIKELY,      // Weak positive signal (AI says likely but not certain)
    PENDING,     // Awaiting enrichment (aggregator EU jobs with stub description)
    REJECTED,    // Negative patterns matched (e.g., "must have right to work")
    UNKNOWN      // No signal detected, AI inconclusive or disabled
}
```

## Configuration Schema

### profile.yaml additions

```yaml
filters:
  visa-sponsorship:
    # Countries where visa detection is required (ISO-like names, case-insensitive)
    target-countries:
      - "netherlands"
      - "austria"
      - "switzerland"
      - "ireland"
      - "sweden"
      - "denmark"
      - "finland"
      - "spain"
      - "belgium"
      - "france"
      - "portugal"
      - "luxembourg"

    # Regex for Germany detection (bypass visa check entirely)
    de-patterns:
      - "\\bgermany\\b"
      - "\\bdeutschland\\b"

    # Regex for remote-EU locations that should trigger visa check
    remote-eu-patterns:
      - "remote\\s*-\\s*(eu|europe|emea)"
      - "remote.*europe"
      - "europe.*remote"

    # Positive patterns: presence → CONFIRMED
    positive-patterns:
      - "visa\\s+sponsor"
      - "sponsorship\\s+(available|provided|offered|included)"
      - "relocation\\s+(package|support|assistance)"
      - "we\\s+sponsor\\s+visa"
      - "work\\s+permit\\s+(support|assist)"
      - "visa\\s+assistance"
      - "immigration\\s+support"
      - "willing\\s+to\\s+sponsor"
      - "sponsoring\\s+(work\\s+)?visa"

    # Negative patterns: presence → REJECTED (overrides positives)
    negative-patterns:
      - "must\\s+(already\\s+)?have\\s+(the\\s+)?(right|permission)\\s+to\\s+work"
      - "no\\s+visa\\s+sponsor"
      - "unable\\s+to\\s+(provide\\s+)?sponsor"
      - "cannot\\s+sponsor"
      - "will\\s+not\\s+sponsor"
      - "valid\\s+work\\s+(permit|authorization)\\s+required"
      - "eu\\s+(citizen|passport|nationals?)\\s+only"
      - "right\\s+to\\s+work\\s+in\\s+the\\s+(eu|uk|netherlands|ireland)"

    # Action when detection returns UNKNOWN (no patterns matched, AI disabled/inconclusive)
    unknown-action: skip  # skip | keep

    # AI fallback settings
    ai-fallback:
      enabled: false
      max-description-chars: 4000
      daily-limit: 50
```

### PersonalProfile record additions

```java
public record VisaSponsorshipFilterConfig(
    List<String> targetCountries,
    List<String> dePatterns,
    List<String> remoteEuPatterns,
    List<String> positivePatterns,
    List<String> negativePatterns,
    String unknownAction,         // "skip" or "keep"
    AiFallbackConfig aiFallback
) {}

public record AiFallbackConfig(
    boolean enabled,
    int maxDescriptionChars,
    int dailyLimit
) {}
```

Add to `FilterConfig`:

```java
public record FilterConfig(
    RoleFilterConfig role,
    LocationFilterConfig location,
    YoeFilterConfig yoe,
    LanguageFilterConfig language,
    VisaSponsorshipFilterConfig visaSponsorship  // NEW
) {}
```

### LocationFilterConfig extension

```java
public record LocationFilterConfig(
    List<String> targetCities,
    List<String> remotePatterns,
    List<String> targetEuCountries  // NEW: enables EU jobs to pass location filter
) {}
```

## Integration Points

### 1. LocationFilter widening

`LocationFilterImpl` constructor reads new `targetEuCountries` from config and compiles an additional `Pattern euCountriesPattern`. The `filter()` method adds a third check: if location matches EU country pattern → KEEP. This allows EU jobs to pass location filter so they reach the visa filter.

### 2. CrawlService insertion (after location filter, before YOE)

```
// Existing: line ~206
FilterResult locationResult = locationFilter.filter(rawJob.location());
if (locationResult.decision() == FilterDecision.SKIP) {
    filterResult = locationResult;
} else {
    // NEW: visa sponsorship filter for non-DE EU jobs
    VisaFilterResult visaResult = visaSponsorshipFilter.filter(
        extractCountry(rawJob.location()), rawJob.location(), rawJob.description());
    if (visaResult.decision() == FilterDecision.SKIP) {
        filterResult = FilterResult.skip(visaResult.reason());
    } else {
        // Continue to YOE filter...
        // After all filters pass, before persist:
        // posting.setVisaSponsorship(visaResult.visaSponsorship());
    }
}
```

### 3. AggregatorIngestionServiceImpl insertion

Same pattern in `applyFilters()` method — add visa check between location and YOE filters. Return `VisaFilterResult` alongside `FilterResult` so caller can set entity field.

### 4. OpportunityScorer enhancement

In `computeLocationFactor()`:

```java
private int computeLocationFactor(JobPosting job) {
    // ... existing logic ...

    // NEW: Visa-confirmed non-DE EU job gets full location factor
    if (isEuCountry(countryLower) && job.getVisaSponsorship() != null) {
        return switch (job.getVisaSponsorship()) {
            case CONFIRMED -> 100;  // Full credit (same as preferred location)
            case LIKELY -> 85;      // Partial boost
            default -> 70;          // Existing EU baseline
        };
    }

    // Existing: EU countries get partial credit
    if (isEuCountry(countryLower)) return 70;
    return 30;
}
```

### 5. DTO + Dashboard

**JobSummaryDto** — add field:

```java
public record JobSummaryDto(
    // ... existing fields ...
    String visaSponsorship  // NEW: nullable, "CONFIRMED" | "LIKELY" | null
) {}
```

**DtoMapper** — map from entity:

```java
job.getVisaSponsorship() != null ? job.getVisaSponsorship().name() : null
```

**TypeScript type** — add to `Job` interface:

```typescript
export type VisaSponsorship = 'CONFIRMED' | 'LIKELY' | 'REJECTED' | 'UNKNOWN';

export interface Job {
  // ... existing fields ...
  visaSponsorship?: VisaSponsorship | null;
}
```

**VisaBadge component** — shown on JobCard when `visaSponsorship` is CONFIRMED or LIKELY:

```tsx
// dashboard/src/components/VisaBadge.tsx
function VisaBadge({ status }: { status: 'CONFIRMED' | 'LIKELY' }) {
  const config = {
    CONFIRMED: { label: 'Visa ✓', color: 'bg-green-500/10 text-green-400 ring-green-500/20', tooltip: 'Visa sponsorship confirmed' },
    LIKELY: { label: 'Visa ~', color: 'bg-yellow-500/10 text-yellow-400 ring-yellow-500/20', tooltip: 'Visa sponsorship likely' },
  };
  // renders pill badge inline with location/remote badges
}
```

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Filter position | After LocationFilter, before YOE | Location must pass first (job must be in target EU country); visa check needs description (available at this point) | After all filters (post-filter enrichment) | Earlier = fewer wasted resources; but slightly increases filter chain complexity |
| Regex-first strategy | Regex always runs, AI only on UNCLEAR | Most visa-friendly JDs have explicit keywords; regex is free and fast | AI-only, parallel regex+AI | Regex misses implicit signals; but AI costs money and is slower. Regex handles ~20% of EU jobs accurately (those with explicit wording) |
| Negative overrides positive | If both positive and negative patterns match, REJECTED wins | Ambiguous JDs ("visa sponsor available BUT must have EU work permit") should be conservative | Positive wins, flag for review | May reject some valid jobs; but avoids applying where sponsorship won't happen |
| Config in profile.yaml | Patterns and countries in profile.yaml, not application.yaml | Matches existing filter/scoring config location; all user-facing config in one file | application.yaml, separate visa.yaml | Consistent with LocationFilter, MatchScorer pattern |
| Null visa field for DE jobs | DE jobs get `visaSponsorship = null` | DE jobs never need visa detection; null distinguishes "not evaluated" from UNKNOWN (evaluated, inconclusive) | Set CONFIRMED for all DE | Cleaner semantics; dashboard can hide badge for null |
| AI default disabled | `ai-fallback.enabled: false` | Cost control; user opts in when wanting broader recall | Default enabled | ~80% of sponsoring EU jobs without explicit keywords will be skipped; acceptable for initial rollout |
| UNKNOWN action configurable | `unknown-action: skip` default | Conservative default (don't clutter feed with unconfirmed EU jobs); user can switch to `keep` for broader coverage | Always skip, always keep | `skip` = low noise but misses jobs; `keep` = more results but false positives |
| Widen LocationFilter | Add `targetEuCountries` to existing LocationFilterConfig | Minimal change; reuses existing pattern compilation infrastructure | Separate EuLocationFilter class | Keeps one location filter (single responsibility = "what locations do we accept"); avoids proliferating filter beans |

## German Pipeline Isolation Guarantees

The existing German job pipeline MUST remain completely unaffected by this feature. The following invariants are enforced:

### Invariants

| # | Guarantee | Enforcement Mechanism |
|---|-----------|----------------------|
| 1 | DE jobs never trigger visa detection logic | `VisaSponsorshipFilter`: first check is `if country matches DE-patterns → return KEEP immediately`. Short-circuits before `VisaDetectionChain` is invoked. |
| 2 | DE jobs have `visaSponsorship = null` (never set) | Filter returns `VisaFilterResult(KEEP, null, null)` for DE — null visa field propagated to entity. |
| 3 | LocationFilter DE/remote behavior unchanged | New `targetEuCountries` pattern is an ADDITIONAL match branch. Existing `targetCitiesPattern` and `genericRemotePattern` evaluated first, same logic, same patterns. |
| 4 | Scoring unchanged for DE jobs | `OpportunityScorer.computeLocationFactor()` gates new visa logic behind `if (visaSponsorship != null)`. DE jobs have null → existing code path executes. |
| 5 | No backfill migration | New `visa_sponsorship` column is nullable, no DEFAULT, no UPDATE on existing rows. All existing DE jobs stay null. |
| 6 | Dashboard unchanged for DE jobs | `VisaBadge` only renders when `visaSponsorship` is CONFIRMED or LIKELY. Null → component not rendered. |
| 7 | DTO backward compatible | `visaSponsorship` field is nullable in DTO/JSON. Null omitted from serialization. Existing API consumers see no difference. |
| 8 | Null country = KEEP (backward compat) | If `locationCountry` is null/blank (common for older DE jobs), `VisaSponsorshipFilter` returns KEEP with null visa field. Assumes DE. |
| 9 | Filter chain ordering preserved | VisaSponsorshipFilter inserts AFTER LocationFilter. If LocationFilter already passed a DE job, visa filter is a no-op. Existing filters (Language, Role, YOE, Dedup) untouched. |
| 10 | No config required for DE-only operation | If `filters.visa-sponsorship` section is absent from profile.yaml, filter is effectively disabled (empty target-countries → no non-DE jobs reach detection chain). System behaves identically to today. |

### Endpoint Health Semantics

Visa filtering does NOT affect endpoint health status:

```
EU endpoint returns 10 jobs → last_crawl_status = SUCCESS (endpoint healthy)
  → 8 jobs visa-filtered (SKIP) → normal filter behavior
  → 2 jobs pass → stored
  → Endpoint shows: fetched=10, created=2, filtered=8
```

- `last_crawl_status` reflects HTTP/extraction success, NOT post-filter outcomes
- `consecutive_errors` only increments on extraction failures (HTTP errors, parse errors, timeouts)
- An EU endpoint that works but produces 0 stored jobs (all visa-filtered) = **healthy endpoint, strict filter**
- DE endpoints: completely unaffected (visa filter never runs on their jobs)

### Test Regression Requirements

Any PR implementing this feature MUST include:

1. Regression test: DE job with Berlin location → passes all filters, `visaSponsorship = null`, score unchanged
2. Regression test: Remote-DACH job → passes as before (no visa check for DE-remote patterns)
3. Regression test: Existing DE endpoint crawl produces same results with feature enabled
4. Regression test: `OpportunityScorer` for DE job produces identical score before/after

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| High false-negative rate (regex misses) | ~80% of sponsoring EU jobs silently dropped (with AI disabled) | High | AI fallback exists for when user opts in; can refine patterns iteratively based on seen descriptions |
| False positive on "visa" substring | Jobs with "visa" in company name or unrelated context pass filter | Low | Patterns use compound phrases (`visa\s+sponsor`), not bare keywords; negative patterns override |
| LocationFilter change widens too much | Non-target EU jobs pass location filter but have no description → null visa → SKIP | Med | LocationFilter only passes if country in explicit `targetEuCountries` list; not a wildcard |
| AI cost creep | If AI enabled and many EU jobs, cost grows linearly | Med | `daily-limit` config caps AI calls per day; disabled by default |
| Country parsing failures | `locationCountry` field is null/blank for some jobs | Med | VisaSponsorshipFilter returns KEEP on null country (preserves existing DE-assumed behavior) |
| Negative pattern too broad | Pattern like "right to work" accidentally matches positive contexts | Low | Use multi-word compound patterns with `\s+` boundaries; test against real descriptions in unit tests |
| Remote-EU ambiguity | "Remote - Europe" jobs that don't sponsor still enter pipeline | Med | Treated same as non-DE EU (requires visa signal to pass); `unknown-action: skip` handles gracefully |

## Test Plan

### Unit Tests

**RegexVisaDetectionStrategy**:
- Happy: description with "visa sponsorship available" → CONFIRMED, confidence ≥ 0.9
- Happy: description with "relocation package included" → CONFIRMED
- Negative override: description with "visa sponsorship" AND "must have right to work in EU" → REJECTED
- No match: generic job description with no visa keywords → UNCLEAR (not UNKNOWN — that's chain's job)
- Null/blank input → UNKNOWN
- Multiple positive matches → CONFIRMED (first match sufficient)
- Case insensitivity: "Visa Sponsorship" and "VISA SPONSORSHIP" both match

**AiVisaDetectionStrategy**:
- Mock AI returns "yes" → CONFIRMED
- Mock AI returns "no" → REJECTED
- Mock AI returns "unclear" → UNKNOWN
- AI timeout → UNKNOWN (fail-open), warning logged
- AI error (4xx/5xx) → UNKNOWN, warning logged
- Description truncated to `maxDescriptionChars` before sending
- Daily limit exceeded → UNKNOWN without calling AI

**VisaDetectionChain**:
- Regex CONFIRMED → returns immediately (AI never called)
- Regex REJECTED → returns immediately
- Regex UNCLEAR + AI disabled → UNKNOWN
- Regex UNCLEAR + AI enabled → AI result returned
- Regex UNCLEAR + AI enabled + AI returns UNKNOWN → UNKNOWN

**VisaSponsorshipFilter**:
- Country = "Germany" → KEEP, visaSponsorship = null
- Country = "Netherlands" + CONFIRMED → KEEP, visaSponsorship = CONFIRMED
- Country = "Netherlands" + UNKNOWN + unknown-action=skip → SKIP
- Country = "Netherlands" + UNKNOWN + unknown-action=keep → KEEP, visaSponsorship = UNKNOWN
- Country = null → KEEP (backward compat)
- Location matches remote-EU pattern → runs detection chain
- Country not in target list and not DE → SKIP (not our target region)

**LocationFilterImpl (widened)**:
- Existing DE tests unchanged (regression)
- New: "Amsterdam, Netherlands" → KEEP (if Netherlands in targetEuCountries)
- New: "Paris, France" → KEEP (if France in targetEuCountries)
- Country not in either list → SKIP

**OpportunityScorer (visa integration)**:
- EU job with CONFIRMED → locationFactor = 100
- EU job with LIKELY → locationFactor = 85
- EU job with null → locationFactor = 70 (existing behavior)
- DE job → existing logic unchanged

### Integration Tests

- End-to-end ingestion: EU job with "visa sponsorship provided" in description → persisted with `visaSponsorship = CONFIRMED`, `languageFilter = KEEP`
- End-to-end ingestion: EU job with no visa keywords → persisted with `languageFilter = SKIP` (or KEEP if unknown-action=keep), `filterReason` contains "visa"
- End-to-end ingestion: DE job → no visa field set, existing filters apply normally
- Config loading: `PersonalProfileLoader` correctly parses new `visa-sponsorship` section from profile.yaml
- Filter chain ordering: verify VisaSponsorshipFilter runs after Location and before YOE

### End-to-End Tests

- Crawl endpoint for known EU company with visa keywords → job appears in dashboard with visa badge
- Aggregator ingestion of EU job without visa keywords → job filtered out (not in daily feed)
- Dashboard: JobCard shows green "Visa ✓" badge for CONFIRMED job
- Dashboard: JobCard shows yellow "Visa ~" badge for LIKELY job
- Dashboard: No badge shown for DE jobs or null visa status

### Non-Functional Tests

- **Performance**: Regex detection < 1ms per job (compiled patterns, no backtracking-heavy regex). Measured in unit test with 100-iteration loop.
- **AI latency**: When AI enabled, add timeout of 10s per call. Fail-open on timeout.
- **Config hot-reload**: Not required (patterns compiled at construction, consistent with existing filters). Restart required for config changes.
- **Security**: AI prompt injection not a concern (description is classification input, not executed). No user-facing input to this filter.
