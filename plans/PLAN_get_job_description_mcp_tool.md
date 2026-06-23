# Plan: get_job_description MCP Tool

## Overview

Add MCP tool that accepts job UUID, short ID (8 chars), or job posting URL and returns the full job description as plain text (HTML stripped). All API infrastructure exists — just need new tool file + registration.

## Tech Stack

- TypeScript (MCP server)
- Zod (input schema)
- Existing `JobHunterClient` methods (`resolveJobId`, `getJob`, `getJobByUrl`)
- HTML→text stripping (same `stripHtml` util used by `getJobKeywords`)

## Testing Strategy

- Unit: mock client, verify HTML stripping + error messages for null descriptions
- Integration: manual test via Claude MCP or `curl`
- Done when: tool returns clean text for valid job ID, graceful error for missing job / null description

## Phases

### Phase 1: Implement tool

- Step 1: Create `mcp-server/src/tools/getJobDescription.ts` — Zod schema (`job_id: string`), handler resolves ID/URL, fetches job, strips HTML from description, returns plain text
- Step 2: Export from `mcp-server/src/tools/index.ts`
- Step 3: Rebuild (`npm run build` in `mcp-server/`)

## Risks/Edge cases

- **Null description**: Some jobs have no description yet (pending enrichment) → return informative message "Description not available for this job. It may still be pending enrichment."
- **Very long descriptions**: Some JDs are 10k+ chars → return full text (no truncation; LLM context handles it)
- **URL input**: Handled by existing `client.getJobByUrl()` fallback
- **Job not found**: Return clear error "No job found with ID/URL: {input}"
