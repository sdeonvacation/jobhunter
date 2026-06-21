# Plan: Visa Sponsorship Detection for EU Jobs

## Overview

Extend the job ingestion pipeline to accept jobs from non-German EU tech hubs, gated by a visa sponsorship detection filter. Jobs from target EU countries (and remote-EU) only pass ingestion if visa sponsorship is confirmed via regex or (optionally) AI classification. German jobs remain unaffected.

## Tech Stack

- Java 21 / Spring Boot 3.3.5 (existing)
- Liquibase migration (new column)
- Profile.yaml config (patterns, countries)
- AI provider (existing Anthropic/OpenAI infra, gated behind flag)
- React + Tailwind (dashboard visa badge)

## Testing Strategy

- Unit: VisaDetectionStrategy impls (regex patterns, AI mock), conditional filter logic, config loading
- Integration: End-to-end ingestion of EU job with/without visa keywords, verify stored/skipped correctly
- Done when: EU job with "relocation package" in description passes filter and is stored; EU job with no visa signal is skipped; DE job bypasses visa check entirely; dashboard shows visa badge

## Phases

### Phase 1: Data Model & Configuration

- Step 1: Add `visa_sponsorship` column (VARCHAR/enum) to `job_posting` table via Liquibase migration
- Step 2: Add `VisaSponsorship` enum to model layer (CONFIRMED, LIKELY, REJECTED, UNKNOWN)
- Step 3: Add field to `JobPosting` entity
- Step 4: Add `filters.visa-sponsorship` section to `profile.yaml` with target-countries, positive-patterns, negative-patterns, apply-to-remote-eu, unknown-action, ai-fallback.enabled
- Step 5: Add config record to `PersonalProfile` and loading logic in `PersonalProfileLoader`

### Phase 2: Detection Strategy Interface & Regex Implementation

- Step 1: Create `VisaDetectionStrategy` interface with `VisaDetectionResult detect(String description)` returning enum + confidence
- Step 2: Implement `RegexVisaDetectionStrategy` — reads positive/negative patterns from profile config, compiles once at construction. Negative patterns override positives. Returns CONFIRMED/REJECTED/UNCLEAR
- Step 3: Implement `AiVisaDetectionStrategy` — gated by `ai-fallback.enabled` flag (default: false). Classifies description via existing AI provider infra. Returns CONFIRMED/REJECTED/UNKNOWN
- Step 4: Create `VisaDetectionChain` that orchestrates strategies in order: regex first → if UNCLEAR and AI enabled → AI fallback → else return UNCLEAR

### Phase 3: Conditional Filter Integration

- Step 1: Expand `LocationFilterImpl` to accept target EU countries from config (add to `profile.yaml` location section alongside existing target-cities)
- Step 2: Create `VisaSponsorshipFilter` component that wraps the detection chain. Input: job location + description. Logic: if location is DE → skip filter (return KEEP). If location is non-DE EU or remote-EU → run detection chain → CONFIRMED/LIKELY → KEEP, REJECTED/UNKNOWN → SKIP
- Step 3: Integrate `VisaSponsorshipFilter` into `CrawlService` pipeline (after location filter, before persistence)
- Step 4: Integrate into `AggregatorIngestionServiceImpl` pipeline (same position)
- Step 5: When job passes, set `job.visaSponsorship` field before persisting

### Phase 4: Scoring Integration

- Step 1: Update `OpportunityScorer` — for non-DE jobs with CONFIRMED visa, give full location factor (100). LIKELY gets partial boost (85)
- Step 2: No changes needed for DE jobs (existing scoring unchanged)

### Phase 5: Dashboard UI

- Step 1: Add `visaSponsorship` field to job DTO and TypeScript types
- Step 2: Add visa badge component (icon + tooltip) on JobCard for non-DE jobs
- Step 3: Show badge only when visaSponsorship = CONFIRMED or LIKELY

### Phase 6: EU Endpoint Expansion

- Step 1: Add career endpoints for known visa-friendly companies in target countries (Netherlands, Austria, Switzerland, Ireland, Sweden, Denmark, Finland, Spain)
- Step 2: Verify aggregator sources (BSJ, Arbeitnow, LinkedIn) surface EU jobs correctly with widened location filter

## Risks/Edge cases

- **False negatives (regex misses)**: Most EU job descriptions say nothing about visa. With AI disabled (default), ~80% of sponsoring jobs will be silently dropped. Mitigation: AI flag exists for when user wants broader recall.
- **Regex false positives**: "visa" in company name, "sponsor" in unrelated context. Mitigation: patterns require compound phrases (e.g., `visa\s+sponsor`), not bare keywords.
- **Negative overrides**: Job says "visa sponsorship available" AND "must have right to work in [other country]". Mitigation: negative patterns take priority; AI fallback can resolve ambiguity when enabled.
- **Remote-EU ambiguity**: "Remote - Europe" could mean company sponsors OR only hires where you already have authorization. Mitigation: treated same as non-DE EU (requires visa signal).
- **Location parsing**: Job location string might not cleanly map to country. Mitigation: reuse existing `locationCountry` field already parsed by extractors.
- **AI cost creep**: If AI enabled and many EU jobs flow in, cost scales linearly. Mitigation: gated behind flag with default false; batch-size and daily-limit configurable.
