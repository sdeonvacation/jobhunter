---
description: Extract all prior resumes submitted to one company, classify divergence as healthy tailoring vs factual damage, and align the current unsubmitted resume to the immutable prior set.
when_to_use: Before submitting a new tailored resume when `reactive-resume_list_resumes` shows 2+ resumes for the same company, or when the user asks to "compare resumes", "evaluate divergence", "find inconsistencies", or "align current to previous applications".
---

## Context

Submitted applications cannot be changed — only the current working copy can be edited.
Divergence across applications to the same employer has two layers:

- **Selection layer** (bullets/skills/projects/headline-strand swapped per JD): healthy
  tailoring, expected, NOT damage. Omission of a bullet present in a prior is not a
  contradiction. Subsets (e.g. GCP omitted where a prior listed AWS/Azure/GCP) are fine.
- **Factual layer** (job titles, headline seniority, years count, metrics for identical
  work, dates, education, certs, links): damage if they differ — a reviewer comparing
  two applications sees inflation or confusion.

A wrong alignment direction is the classic failure: in the Snowflake session the agent
tried to align submitted priors to the current resume and was corrected harshly.
`tailor-resume` SKILL.md §27 only lists which fields must match; it does not cover this
extraction/classification/alignment workflow.

## Steps

1. **Enumerate** — `reactive-resume_list_resumes`, filter names `cv-sam-maurya-<Company>-*`.
   Mark every copy except the current working one as FROZEN (already submitted).

2. **Fetch every version's full JSON.**
   - 1-2 priors: `reactive-resume_read_resume` per id.
   - 3+ priors (context flooding): fetch programmatically in a sandbox and diff with a
     script instead of reading into context. The reactive-resume MCP server is at
     `http://localhost:3001` (JSON-RPC `tools/call`, tool `read_resume`) with an
     `x-api-key` header — key lives in `/Users/maurya/projects/jobhunter/.opencode/opencode.json`.
     Verify endpoint/key against that file before use.

3. **Classify every diff** into selection layer (report as healthy, no action) vs
   factual layer (damage candidate). Compare field-by-field: headline, profile
   (years + closing), per-role job titles, every shared bullet's metric, dates,
   education, certifications, links, location.

4. **Build a damage table ranked by severity:**
   1. Role/job-title mismatch — #1 cross-application check; worst when current is the
      odd one out N-to-1 (e.g. `AI Platform Engineer` vs 3× `Platform Engineer`).
   2. Headline seniority (`Senior Software Engineer` vs `Software Engineer` ×3).
   3. Years (`5+ years` vs `4+ years` ×3).
   4. Metric drift on identical work, especially same sentence stem
      (JVM GC: "cutting OOM incidents by 60%" vs "reducing p95 tail latency by ~40ms").
   Low/none: abbreviation cosmetics (`Jul`/`July`), superset cloud lists, bullet order.

5. **Frozen-set contradictions** (priors disagree with each other — no alignment
   satisfies all): align to majority (2-of-3) or to the latest submitted version.
   Put them in a separate table. Ask the user only for truth-dependent rows (real job
   title, real years) — only they know.

6. **Check candidate fixes against `~/Documents/ResumePoints_Sambhrant.md`:** submitted
   phrasings may have drifted from the current source (old 60%/80% metrics no longer in
   the file). Direction rule: align current to the SUBMITTED wording even when the
   source says something else; flag source drift in the report, never "fix toward source".

7. **Patch ONLY the current resume** — one RFC 6902 patch (see
   `reactive-resume-patch-recovery` for failure handling). Never edit a frozen copy.

8. **Report:** (a) clean list — no exposure; (b) material-mismatch table with severity;
   (c) frozen-set table; (d) decisions needed from user; (e) after patching, re-read and
   verify every aligned field landed.

## Gotchas

- **Direction is fixed: current moves toward priors, never the reverse.** Priors are
  immutable snapshots of submitted applications.
- Bullet-set / skills / projects divergence itself is tailoring, not damage — do not
  "reconcile" it.
- Headline keyword-strand differences are cosmetic UNLESS seniority words differ.
- Don't patch until the user resolves truth-dependent rows (title, years).
- Within a normal tailor run, do the lightweight version too: cross-version check
  against the latest prior for the same company (headline, titles, major metrics).

## Templates

Damage table (from the Snowflake 4-version audit):

| # | Fact | Submitted (all N) | Current | Severity |
|---|------|-------------------|---------|----------|
| 1 | BTP job title | `Platform Engineer, BTP Integration Cell` (×3) | `AI Platform Engineer, ...` | Worst — odd one out 3-to-1 |
| 2 | Headline seniority | `Software Engineer` (×3) | `Senior Software Engineer \| ...` | High |
| 3 | Years | `4+ years` (×3) | `5+ years` | High |
| 4 | JVM GC outcome | `cutting OOM incidents by 60%` | `reducing p95 tail latency by ~40ms` | Medium — same sentence stem |

Frozen-set table:

| Fact | Split among submitted | Current | Verdict |
|------|----------------------|---------|---------|
| Edge title | 2× `Platform Engineer` / 1× `Software Engineer` | matches latest | keep (latest anchor) |
| Multi-worker metric | 2× `eliminating recurring OOM crashes` / 1× `reducing by 80%` | matches 2/3 | leave as-is (majority) |
