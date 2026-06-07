# Plan: LinkedIn MCP Integration

## Overview

Integrate the [linkedin-mcp-server](https://github.com/stickerdaniel/linkedin-mcp-server) as a Docker sidecar to enable LinkedIn job discovery, company enrichment, networking/outreach, and profile intelligence. LinkedIn jobs flow through the same filter/score pipeline as all other ATS sources.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| LinkedIn MCP Server | Python (Patchright/Chromium), Docker sidecar |
| Transport | Streamable HTTP (port 8000, path /mcp) |
| API Client | New `HttpMcpClient` in Spring Boot (WebClient-based) |
| Auth | Browser profile volume mount (~/.linkedin-mcp) |
| Rate Limiting | Token bucket per tool category |
| Storage | Existing PostgreSQL (new columns/tables as needed) |

## Testing Strategy

- Unit: Mock `HttpMcpClient` responses, test provider logic, test DTO mapping
- Integration: Testcontainers with WireMock simulating LinkedIn MCP HTTP responses
- E2E: Manual verification with real LinkedIn MCP container (browser-dependent)
- Done when: LinkedIn jobs appear in dashboard, company enrichment populates, outreach commands work via JobHunter MCP tools

## Phases

### Phase 1: Infrastructure (Docker Sidecar + HTTP MCP Client)

- Step 1: Add `linkedin-mcp` service to docker-compose.yml (streamable-http transport, volume mount for browser profile, health check)
- Step 2: Create `HttpMcpClient` (WebClient → JSON-RPC 2.0 over HTTP, replaces stdio sidecar pattern for persistent servers)
- Step 3: Add config section in application.yaml (`linkedin-mcp.base-url`, `enabled`, rate-limit settings)
- Step 4: Add health endpoint that pings LinkedIn MCP `/mcp` and reports session validity
- Step 5: Document one-time auth setup (`uvx linkedin-scraper-mcp@latest --login` on host, mount profile into Docker)

### Phase 2: Job Discovery

- Step 1: Create `LinkedInJobProvider` implementing `DiscoveryProvider` (calls `search_jobs` tool with keywords + locations from profile.yaml)
- Step 2: Map LinkedIn job results → `DiscoveredCompany` (extract company name, job URL as career hint)
- Step 3: Create `LinkedInExtractor` implementing `JobExtractor` for `AtsType.LINKEDIN` (calls `get_job_details` with LinkedIn job ID/URL)
- Step 4: Map job details → `RawJobData` (title, description, location, salary, apply URL)
- Step 5: Enable `DiscoverySource.LINKEDIN` and wire into crawl scheduler (lower frequency: every 6h to avoid rate limits)
- Step 6: Add LinkedIn search config to profile.yaml (`discovery.providers.linkedin.keywords`, `locations`, `experience_level`, `job_type`)

### Phase 3: Company Enrichment

- Step 1: Create `LinkedInCompanyEnricher` service (calls `get_company_profile` with sections: about, jobs)
- Step 2: Add enrichment fields to Company entity: `linkedinUrl`, `industry`, `employeeCount`, `specialties`, `recentPostsSummary`
- Step 3: Create `get_company_posts` integration for activity signals (hiring surge detection: many recent job posts = actively hiring)
- Step 4: Wire enrichment into discovery pipeline (after company created/resolved, trigger async enrichment)
- Step 5: Expose enrichment data in dashboard Company detail view
- Step 6: Liquibase migration for new Company columns

### Phase 4: Networking & Outreach

- Step 1: Create `LinkedInNetworkingService` (wraps `get_company_employees`, `connect_with_person`, `send_message`)
- Step 2: Add `search_people` integration (find recruiters/hiring managers at target companies, filtered by title keywords: "recruiter", "hiring manager", "engineering manager", "head of engineering")
- Step 3: Add `OutreachContact` entity (personId, name, title, company, connectionStatus, lastContactedAt, notes)
- Step 4: Expose 3 new MCP tools in jobhunter-mcp: `find_contacts` (search people at company), `connect_with` (send connection request with note), `send_linkedin_message`
- Step 5: Add safety guards: daily connection request limit (configurable, default: 5), message cooldown per person (7 days), confirmation prompts
- Step 6: Liquibase migration for OutreachContact table

### Phase 5: Profile Intelligence

- Step 1: Create `LinkedInProfileService` (wraps `get_person_profile` with sections: experience, skills, posts)
- Step 2: Expose `research_person` MCP tool in jobhunter-mcp (input: LinkedIn URL or name + company, output: structured profile summary)
- Step 3: Add `get_sidebar_profiles` integration for discovering related people (e.g., "People also viewed" on hiring manager profile)
- Step 4: Cache profile lookups in DB (avoid re-scraping same person within 7 days)
- Step 5: Add `ProfileCache` entity (linkedinUrl, profileData JSONB, fetchedAt, expiresAt)
- Step 6: Liquibase migration for ProfileCache table

### Phase 6: Dashboard & MCP Tool Updates

- Step 1: Add LinkedIn source badge to JobCard (distinguish LinkedIn-sourced jobs)
- Step 2: Display job poster on LinkedIn JobCards (name, title, LinkedIn URL — extracted during crawl via `get_person_profile` or job metadata)
- Step 3: Add "Connect" button on job poster (confirmation dialog → calls POST /api/linkedin/contacts/{id}/connect, checks daily limit)
- Step 4: Add "Message" button on job poster (only if CONNECTED status, confirmation dialog with message textarea → calls POST /api/linkedin/contacts/{id}/message)
- Step 5: Add Company detail page section: LinkedIn insights (industry, size, recent activity, contacts found)
- Step 6: Update jobhunter-mcp tool list: add `find_contacts`, `connect_with`, `send_linkedin_message`, `research_person`
- Step 7: Add LinkedIn connection status indicator on company cards (shows if you have contacts there)
- Step 8: Update README with LinkedIn MCP setup instructions

## Risks

| Risk | Mitigation |
|------|-----------|
| LinkedIn rate limiting / account restrictions | Token bucket rate limiter (max 50 requests/hour), randomized delays between calls, lower crawl frequency (6h) |
| Browser session expiry | Health check monitors session validity, alert via dashboard Health page, document re-auth procedure |
| LinkedIn MCP tool instability (connect_with_person, send_message have open issues) | Phase 4 marked as experimental, graceful degradation if tool fails, retry with backoff |
| Chromium memory usage in Docker | Resource limits in docker-compose (mem_limit: 2G), periodic container restart via healthcheck |
| LinkedIn ToS concerns | User-initiated actions only (no automated mass outreach), configurable daily limits, confirmation prompts for destructive actions |
| Cold start latency (browser launch) | Keep container running (restart: unless-stopped), pre-warm on compose up |
| Profile volume mount cross-platform issues | Document macOS/Linux paths, provide setup script for initial auth |
