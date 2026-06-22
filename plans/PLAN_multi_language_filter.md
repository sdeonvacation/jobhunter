# Plan: Multi-Language Filter

## Overview

Extend the language filter from German-only detection to reject ALL non-English JDs and non-English language requirements. Languages to detect are configurable via profile.yaml. Keep soft qualifier logic (e.g., "Dutch nice to have" still passes).

## Tech Stack

- Java 21, Spring Boot 3.3.5
- Lingua library (already in deps) — extend from binary German/English to configurable multi-language
- profile.yaml for all configuration (detected languages, exclude patterns, thresholds)

## Testing Strategy

- Unit: Test Lingua detection for Dutch, Spanish, Swedish, French JDs
- Unit: Test exclude patterns for each new language requirement
- Unit: Soft qualifier negation works for all languages
- Unit: Config loading from profile.yaml for language list
- Done when: Non-English JDs from Netherlands/Spain/Sweden endpoints get SKIP

## Phases

### Phase 1: Make Lingua Detection Configurable

- Step 1: Add `filters.language.detect-languages` list to profile.yaml (e.g., `[german, dutch, spanish, swedish, french, italian, portuguese, polish]`)
- Step 2: Change `LanguageDetectorBuilder.fromLanguages(GERMAN, ENGLISH)` → build from configured list + ENGLISH. **If list is empty/absent, skip Lingua detection entirely (no-op).**
- Step 3: Change detection logic: instead of "is German?" → "is NOT English?" (if detected language != ENGLISH and confidence >= threshold → SKIP)
- Step 4: Make confidence threshold configurable: `filters.language.confidence-threshold: 0.85`
- Step 5: Update filter reason from `"German JD"` → `"non-English JD ({detected_language})"`
- Step 6: **Remove ALL hardcoded language names, default patterns, and DEFAULT_EXCLUDE_PATTERNS constant from Java code.** Everything comes exclusively from profile.yaml.

### Phase 2: Extend Exclude Patterns for EU Languages

- Step 1: Add language requirement patterns to profile.yaml per language:
  - German (existing): `"german\\s+c[12]"`, `"deutsch\\s+c[12]"`, `"flie[ßs]end\\s+deutsch"`, etc.
  - Dutch: `"dutch\\s+c[12]"`, `"nederlands"`, `"vloeiend\\s+nederlands"`, `"moedertaal"`
  - Spanish: `"spanish\\s+c[12]"`, `"español"`, `"castellano"`, `"fluidez.*español"`
  - Swedish: `"swedish\\s+c[12]"`, `"svenska"`, `"flytande\\s+svenska"`
  - French: `"french\\s+c[12]"`, `"français"`, `"courant.*français"`, `"langue\\s+maternelle"`
- Step 2: Keep single flat list of exclude-patterns (all languages combined) — existing logic already handles this. **If list is empty/absent, pattern matching is disabled (no-op).**
- Step 3: Update filter reason from `"German C1/C2 required"` → `"non-English language required"`

### Phase 3: Backfill & Refilter

- Step 1: Add admin endpoint `POST /api/admin/refilter-language` to re-run language filter on all active KEEP jobs that have descriptions
- Step 2: Run refilter to catch existing Dutch/Spanish/Swedish JDs already in DB

## Risks/Edge cases

- **Mixed-language JDs**: Job has English requirements + Dutch company intro → Lingua may misclassify. Mitigation: ≥85% confidence threshold; pattern matching catches explicit requirements regardless.
- **Short descriptions**: < 100 chars can't be reliably detected. Mitigation: Keep MIN_TEXT_LENGTH threshold; pattern matching still works on short text.
- **Performance**: More languages = slightly heavier detector. Mitigation: Lingua is in-process, focused set (8-10 languages) not all 75+. Negligible overhead.
- **False positives**: English JDs with scattered non-English words (company names, city names). Mitigation: 0.85 confidence threshold is conservative.
- **New languages**: Adding a new target country just requires adding its language to `detect-languages` list and patterns to `exclude-patterns` in profile.yaml. No code change needed.
