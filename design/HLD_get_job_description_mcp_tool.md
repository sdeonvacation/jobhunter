# HLD: get_job_description MCP Tool

## Tech Stack

| Category  | Technology | Purpose                        |
| --------- | ---------- | ------------------------------ |
| Language  | TypeScript | MCP server consistency         |
| Schema    | Zod        | Input validation               |
| Client    | JobHunterClient | API access (existing)     |

## Components

| Component | Responsibility | Dependencies |
| --------- | -------------- | ------------ |
| `getJobDescription.ts` | Accept job ID/URL, fetch description, strip HTML, return plain text | JobHunterClient |
| `tools/index.ts` | Export registration (1-line addition) | getJobDescription.ts |

## Architecture

```
User (MCP call)
    │
    ▼
getJobDescription handler
    │
    ├─ isUrl? ──► client.getJobByUrl(url) ──► JobDetail | null
    │                                              │
    └─ ID/short? ► client.resolveJobId() ──► client.getJob(uuid) ──► JobDetail
                                                   │
                                                   ▼
                                          detail.description (HTML)
                                                   │
                                                   ▼
                                            stripHtml() → plain text
                                                   │
                                                   ▼
                                      { content: [{ type: "text", text }] }
```

## Interface

### Input Schema (Zod)

```typescript
const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});
```

### Handler Signature

```typescript
handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) =>
  { content: [{ type: 'text', text: string }] }
```

### Tool Export Object

```typescript
export const getJobDescriptionTool = {
  name: 'get_job_description',
  description: 'Get the full job description as plain text — accepts UUID, short ID (8 chars), or a job posting URL',
  inputSchema,
  handler,
};
```

## Data Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Handler | Check if `job_id` is URL (starts with `http://` or `https://`) | 2a or 2b |
| 2a | Client | `client.getJobByUrl(url)` → JobDetail or null | 3 |
| 2b | Client | `client.resolveJobId(id)` → UUID, then `client.getJob(uuid)` → JobDetail | 3 |
| 3 | Handler | Check `detail.description` not null/empty | 4 |
| 4 | stripHtml | Strip HTML tags, decode entities, collapse whitespace | 5 |
| 5 | Handler | Return `{ content: [{ type: 'text', text: plainText }] }` | Done |

## Handler Pseudocode

```typescript
import { z } from 'zod';
import { JobHunterClient } from '../client.js';

function stripHtml(html: string): string {
  return html
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
}

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

// handler:
if (isUrl(params.job_id)) {
  const job = await client.getJobByUrl(params.job_id);
  if (!job) return error("No job found with ID/URL: {input}");
  // use job.description
} else {
  const fullId = await client.resolveJobId(params.job_id);
  const job = await client.getJob(fullId);
  // use job.description
}

if (!description) return "Description not available for this job. It may still be pending enrichment.";
return stripHtml(description);
```

## Error Handling

| Condition | Response Text |
|-----------|--------------|
| URL not found in DB (`getJobByUrl` returns null) | "No job found with URL: {url}" |
| Short ID / UUID resolution fails (API 404) | Exception propagates → MCP error frame |
| `detail.description` is null/empty | "Description not available for this job. It may still be pending enrichment." |
| API unreachable (network error) | Exception propagates → MCP error frame |

## Registration

Add to `mcp-server/src/tools/index.ts`:

```typescript
export { getJobDescriptionTool } from './getJobDescription.js';
```

## Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Copy `stripHtml`/`isUrl` locally | Inline in file | Matches getJobKeywords pattern; no shared util exists |
| No truncation | Return full text | LLM context handles length; user wants complete JD |
| No LLM processing | Just strip+return | Unlike keywords tool, no AI extraction needed |

## Test Plan

### Unit Tests

- Mock `client.resolveJobId` + `client.getJob` → verify HTML stripped correctly
- Mock `client.getJobByUrl` returning null → verify error message
- Mock job with `description: null` → verify "pending enrichment" message
- Mock job with HTML description → verify clean plain text output

### Manual Integration

- Call via MCP with valid UUID → get text
- Call with 8-char short ID → get text
- Call with job URL → get text
- Call with nonexistent ID → get error
