# HLD: Multi-Language Filter

## Tech Stack

| Category  | Technology         | Purpose                                              |
| --------- | ------------------ | ---------------------------------------------------- |
| Language  | Java 21            | Existing API language                                |
| Framework | Spring Boot 3.3.5  | DI, REST controllers, component scanning             |
| Library   | Lingua             | NLP-based language detection (already in build.gradle)|
| Config    | profile.yaml       | Externalized filter config (languages, thresholds)   |
| Database  | PostgreSQL 16      | Job persistence, filter_reason column                |

## Components

| Component              | Responsibility                                       | Dependencies                       |
| ---------------------- | ---------------------------------------------------- | ---------------------------------- |
| LanguageFilterImpl     | Detect non-English JDs + language requirements       | PersonalProfileLoader, Lingua      |
| PersonalProfile        | Config model for language filter settings            | profile.yaml (via loader)          |
| PersonalProfileLoader  | Parse new config fields from profile.yaml            | profile.yaml file                  |
| AdminController        | Expose refilter-language endpoint                    | LanguageFilter, JobPostingRepository|
| JobPostingRepository   | Query active KEEP jobs for refiltering               | PostgreSQL                         |

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                        profile.yaml                          │
│  filters.language.detect-languages: [german, dutch, ...]     │
│  filters.language.confidence-threshold: 0.85                 │
│  filters.language.exclude-patterns: [...]                    │
└────────────────────────────┬─────────────────────────────────┘
                             │ loaded at startup
                             ▼
┌──────────────────────────────────────────────────────────────┐
│                   PersonalProfileLoader                       │
│  Parses detect-languages + confidence-threshold              │
│  Returns LanguageFilterConfig record                         │
└────────────────────────────┬─────────────────────────────────┘
                             │ injected
                             ▼
┌──────────────────────────────────────────────────────────────┐
│                    LanguageFilterImpl                         │
│                                                              │
│  ┌─────────────────────┐    ┌──────────────────────────┐    │
│  │ Exclude Patterns    │    │ Lingua LanguageDetector   │    │
│  │ (regex, all langs)  │    │ (configurable lang set)   │    │
│  └─────────┬───────────┘    └──────────┬───────────────┘    │
│            │ Step 1: fast regex          │ Step 2: NLP        │
│            ▼                             ▼                    │
│  hasStrictExcludeMatch()       isNonEnglish()                │
│            │                             │                    │
│            └──────────┬──────────────────┘                   │
│                       ▼                                      │
│              FilterResult (KEEP / SKIP + reason)             │
└──────────────────────────────────────────────────────────────┘
         ▲                              ▲
         │ called during crawl          │ called during refilter
         │                              │
┌────────┴─────────┐         ┌─────────┴──────────────┐
│   CrawlService   │         │   AdminController      │
│   AggregatorSvc  │         │   POST /refilter-lang  │
│   LinkedInEnrich │         └────────────────────────┘
└──────────────────┘
```

**Description**: LanguageFilterImpl is the single detection component. It reads configurable language list + threshold from profile.yaml at construction time. Lingua detector is built from the configured language set + ENGLISH. Detection logic flips from "is German?" to "is NOT English?" — any detected non-English language above threshold triggers SKIP. Exclude patterns remain a flat list covering all EU languages. The refilter admin endpoint iterates active KEEP jobs and re-runs the filter, updating decisions in-place.

## Interfaces

### LanguageFilter (unchanged interface)

| Method   | Input                                    | Output       | Behavior                                             | Errors              |
| -------- | ---------------------------------------- | ------------ | ---------------------------------------------------- | ------------------- |
| `filter` | `String jobTitle, String jobDescription` | FilterResult | Check patterns → check Lingua detection → KEEP/SKIP  | None (permissive)   |

### LanguageFilterImpl (internal changes)

| Method              | Input          | Output   | Behavior                                                       | Errors                        |
| ------------------- | -------------- | -------- | -------------------------------------------------------------- | ----------------------------- |
| `isNonEnglish`      | `String text`  | boolean  | Lingua detect → if best != ENGLISH and conf >= threshold → true | Exception → false (permissive)|
| `hasStrictExcludeMatch` | `String text` | boolean | Regex scan + soft qualifier negation (unchanged logic)         | None                          |
| `buildDetector`     | `List<String>` | LanguageDetector | Build from config languages + ENGLISH                   | Invalid language → log + skip |

### PersonalProfile.LanguageFilterConfig (extended record)

| Field                | Type           | Default | Description                              |
| -------------------- | -------------- | ------- | ---------------------------------------- |
| `target`             | String         | "en"    | Target language (existing, unchanged)    |
| `detectLanguages`    | List\<String\> | [] (empty) | Lingua languages to load for detection. If empty, Lingua detection disabled. |
| `confidenceThreshold`| double         | 0.85    | Min confidence to trigger SKIP           |
| `excludePatterns`    | List\<String\> | [] (empty) | Regex patterns for language requirements. If empty, pattern matching disabled. |

**CRITICAL: No hardcoded language names, defaults, or patterns in Java code.** All language configuration lives exclusively in profile.yaml. If `detect-languages` is empty/absent, Lingua detection is skipped. If `exclude-patterns` is empty/absent, pattern matching is skipped. The filter becomes a no-op (always KEEP) without config.

### AdminController (new endpoint)

| Method                  | Input               | Output                  | Behavior                                      | Errors           |
| ----------------------- | ------------------- | ----------------------- | --------------------------------------------- | ---------------- |
| `POST /api/admin/refilter-language` | None (query param: `dryRun=true/false`) | `RefilterResult` record | Re-run filter on active KEEP jobs with descriptions | 500 if DB error |

### RefilterResult (new response DTO)

| Field       | Type | Description                           |
| ----------- | ---- | ------------------------------------- |
| `evaluated` | int  | Total jobs re-evaluated               |
| `filtered`  | int  | Jobs changed from KEEP → SKIP         |
| `kept`      | int  | Jobs that remained KEEP               |

## Data Flow

### Normal Crawl Flow (existing, updated detection logic)

| Step | Component            | Action                                                      | Next               |
| ---- | -------------------- | ----------------------------------------------------------- | ------------------ |
| 1    | CrawlService         | Extract raw job from ATS                                    | LanguageFilterImpl |
| 2    | LanguageFilterImpl   | Check exclude patterns (all EU languages) with soft negation | Step 3 or return SKIP |
| 3    | LanguageFilterImpl   | If text >= 100 chars, run Lingua detection                  | Step 4 or return SKIP |
| 4    | LanguageFilterImpl   | If detected != ENGLISH and confidence >= threshold → SKIP   | Return result      |
| 5    | CrawlService         | Set `languageFilter` and `filterReason` on JobPosting       | Save to DB         |

### Refilter Flow (new)

| Step | Component            | Action                                                      | Next               |
| ---- | -------------------- | ----------------------------------------------------------- | ------------------ |
| 1    | AdminController      | Receive POST /api/admin/refilter-language                   | JobPostingRepository |
| 2    | JobPostingRepository | Query: active=true, languageFilter=KEEP, description NOT NULL | AdminController   |
| 3    | AdminController      | For each job: call languageFilter.filter(title, description)| Step 4             |
| 4    | AdminController      | If SKIP: update job.languageFilter=SKIP, set filterReason   | Step 5             |
| 5    | JobPostingRepository | Save updated jobs (batch)                                   | Return result      |

### Detection Sequence (text-based)

```
Client              AdminController/CrawlService        LanguageFilterImpl             Lingua
  │                         │                                  │                         │
  │── POST /refilter ──────▶│                                  │                         │
  │                         │── filter(title, desc) ──────────▶│                         │
  │                         │                                  │── regex excludePattern ──│
  │                         │                                  │   (Dutch/Spanish/etc.)   │
  │                         │                                  │                          │
  │                         │                                  │── [if no pattern match]  │
  │                         │                                  │   text.length >= 100?    │
  │                         │                                  │                          │
  │                         │                                  │── computeLanguageConf ──▶│
  │                         │                                  │                          │
  │                         │                                  │◀── {DUTCH: 0.92,        │
  │                         │                                  │     ENGLISH: 0.05}      │
  │                         │                                  │                          │
  │                         │                                  │── best != ENGLISH        │
  │                         │                                  │   && 0.92 >= 0.85?       │
  │                         │                                  │   → SKIP("non-English    │
  │                         │                                  │     JD (Dutch)")         │
  │                         │◀── FilterResult.skip(reason) ────│                         │
  │                         │                                  │                         │
  │                         │── update job.languageFilter=SKIP │                         │
  │                         │── update job.filterReason        │                         │
  │◀── {evaluated, filtered, kept} ─│                          │                         │
```

**Error Flows**:
- Lingua throws exception → `isNonEnglish()` returns false → permissive (KEEP)
- Invalid language name in config → log warning at startup, skip that language, build detector with remaining valid languages
- Empty description → immediate KEEP (no detection possible)
- Refilter DB error → 500 response, partial progress committed (per-job saves)

## Data Model

| Entity     | Fields                              | Relationships | Constraints                    |
| ---------- | ----------------------------------- | ------------- | ------------------------------ |
| JobPosting | `languageFilter: FilterDecision`    | Company, Endpoint | ENUM(KEEP, SKIP), default KEEP |
| JobPosting | `filterReason: String`              | —             | Nullable, free text            |

No schema migration needed — `language_filter` and `filter_reason` columns already exist.

## Config Schema (profile.yaml)

```yaml
filters:
  language:
    target: "en"
    # NEW: Languages Lingua should detect (+ ENGLISH always included)
    detect-languages:
      - german
      - dutch
      - spanish
      - swedish
      - french
      - italian
      - portuguese
      - polish
    # NEW: Confidence threshold for non-English detection
    confidence-threshold: 0.85
    # EXISTING (extended): Regex patterns for language requirements
    exclude-patterns:
      # German (existing)
      - "german\\s+c[12]"
      - "deutsch\\s+c[12]"
      - "flie[ßs]end\\s+deutsch"
      - "fluent\\s+german"
      - "muttersprache"
      - "native\\s+german"
      - "german\\s+native"
      - "verhandlungssicher"
      # Dutch (new)
      - "dutch\\s+c[12]"
      - "\\bnederlands\\b"
      - "vloeiend\\s+nederlands"
      - "moedertaal"
      # Spanish (new)
      - "spanish\\s+c[12]"
      - "\\bespa[ñn]ol\\b"
      - "castellano"
      - "fluidez.*espa[ñn]ol"
      # Swedish (new)
      - "swedish\\s+c[12]"
      - "\\bsvenska\\b"
      - "flytande\\s+svenska"
      # French (new)
      - "french\\s+c[12]"
      - "\\bfran[çc]ais\\b"
      - "courant.*fran[çc]ais"
      - "langue\\s+maternelle"
```

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
| -------- | ------ | ------ | ------------ | --------- |
| Detection logic flip | "is NOT English?" instead of "is German?" | Single check covers all configured languages, extensible | Per-language threshold map | Slightly less granular but simpler, one threshold fits all |
| Flat exclude patterns | Single list for all languages | Existing regex scan logic works unchanged, config is additive | Per-language pattern groups | Flat list is simpler; no need to know which language matched (reason says "non-English language required") |
| Config-driven language set | List of language names in profile.yaml | No code change to add a new language; matches existing config pattern. **Zero hardcoded languages in code.** | Hardcoded set, detect ALL Lingua languages | Focused set (8-10) keeps detector lightweight vs all 75+ |
| Refilter as admin endpoint | POST /api/admin/refilter-language | Consistent with existing admin pattern (crawl, score, rescore) | Scheduled job, migration script | On-demand is simpler; one-time operation after config change |
| No new dependencies | Use existing Lingua API | Already proven, `computeLanguageConfidenceValues` returns all languages | Add a second NLP library | Zero dependency risk |
| Permissive on error | Detection failure → KEEP | Avoid false negatives blocking valid jobs | Strict (SKIP on error) | May let occasional non-English through; better than blocking English jobs |
| Confidence from highest non-English | Pick language with max confidence, check if >= threshold | Simple, works for mixed-language edge cases | Check each language independently | If two non-English languages share confidence, still correctly identifies non-English overall |

## Risks

| Risk | Impact | Likelihood | Mitigation |
| ---- | ------ | ---------- | ---------- |
| Mixed-language JDs (English body + Dutch intro) | False SKIP on valid English JD | Low | 0.85 threshold conservative; Lingua needs dominant non-English to hit it |
| Short descriptions (<100 chars) miss detection | Non-English short JDs pass through | Low | Pattern matching still catches explicit requirements; short JDs rare |
| Lingua memory with more languages | Slightly higher JVM heap at startup | Low | 8-10 languages negligible vs 75+; Lingua docs confirm linear scaling |
| Refilter on large dataset slow | Admin endpoint timeout for 10k+ jobs | Med | Process in batches of 500; log progress; no frontend dependency |
| Config typo in language name | Detector built without that language; silent miss | Low | Log warning at startup with invalid name; validate against Lingua enum |
| Dutch/English similarity | Lingua may struggle with short Dutch text | Low | Pattern matching (explicit "nederlands" etc.) catches these deterministically before Lingua runs |

## Test Plan

### Unit Tests

**LanguageFilterImplTest** (extend existing test class):

| Scenario | Input | Expected | Mock |
| -------- | ----- | -------- | ---- |
| Dutch JD detected | 200+ char Dutch text | SKIP("non-English JD (Dutch)") | Real Lingua (multi-lang detector) |
| Spanish JD detected | 200+ char Spanish text | SKIP("non-English JD (Spanish)") | Real Lingua |
| Swedish JD detected | 200+ char Swedish text | SKIP("non-English JD (Swedish)") | Real Lingua |
| French JD detected | 200+ char French text | SKIP("non-English JD (French)") | Real Lingua |
| English JD passes | English description | KEEP | Real Lingua |
| Mixed English/Dutch below threshold | Mostly English + few Dutch words | KEEP | Real Lingua |
| Dutch C1 pattern match | "Dutch C1 required" in English JD | SKIP("non-English language required") | Any |
| Nederlands pattern match | "Vloeiend Nederlands vereist" | SKIP("non-English language required") | Any |
| Soft qualifier negation - Dutch | "Dutch nice to have" | KEEP | Any |
| Soft qualifier negation - French | "French B1 preferred" | KEEP | Any |
| Short text below threshold | 50 char Dutch text | KEEP (too short for detection) | Any |
| Null/blank description | null | KEEP | Any |
| Config loading: detect-languages | profile with [dutch, spanish] | Detector built with DUTCH, SPANISH, ENGLISH | Mock ProfileLoader |
| Config loading: confidence-threshold | profile with 0.90 | Detection uses 0.90 | Mock ProfileLoader |
| Fallback to no-op | No detect-languages in config | Lingua detection disabled, filter always KEEP | Mock ProfileLoader |

**Coverage target**: 95%+ on LanguageFilterImpl

### Integration Tests

| Test | Components | Verification |
| ---- | ---------- | ------------ |
| Refilter endpoint | AdminController + LanguageFilter + DB | POST /api/admin/refilter-language flips Dutch JDs from KEEP → SKIP |
| CrawlService with new filter | CrawlService + LanguageFilter | Dutch JD from crawl gets SKIP with correct reason |
| Config roundtrip | PersonalProfileLoader → LanguageFilterImpl | detect-languages parsed and detector built correctly |

### End-to-End Tests

| Journey | Steps | Success Criteria |
| ------- | ----- | ---------------- |
| New Dutch endpoint crawled | Add NL endpoint → crawl → check jobs | All Dutch JDs have languageFilter=SKIP, filterReason contains "Dutch" |
| Refilter catches existing | Seed Dutch JD with KEEP → call refilter → verify | Job updated to SKIP |
| English JDs unaffected | Crawl endpoint with English JDs | All remain KEEP, no filter_reason set |

### Non-Functional Tests

| Requirement | Target | Verification |
| ----------- | ------ | ------------ |
| Detection latency | < 50ms per JD | Unit test with timing assertion on 200-char text |
| Memory overhead | < 20MB additional heap for 8-lang detector | JVM monitoring during integration test |
| Backward compatibility | Existing config (no detect-languages) still works | Unit test with minimal config |
| Refilter throughput | > 100 jobs/sec | Log timing in refilter endpoint |
