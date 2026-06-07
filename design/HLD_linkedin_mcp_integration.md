# HLD: LinkedIn MCP Integration

## Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| LinkedIn MCP Server | Python + Patchright/Chromium | Browser automation for LinkedIn scraping |
| Transport | Streamable HTTP (JSON-RPC 2.0) | Persistent connection to Docker sidecar (port 8000, path /mcp) |
| API Client | Spring WebClient | Non-blocking HTTP calls to LinkedIn MCP sidecar |
| Container | Docker (stickerdaniel/linkedin-mcp-server:latest) | Isolated browser environment with session persistence |
| Auth | Browser profile volume mount | LinkedIn session stored in ~/.linkedin-mcp |
| Rate Limiting | Token bucket (Bucket4j or custom AtomicLong) | Prevent LinkedIn account restrictions |
| Storage | PostgreSQL 16 + Liquibase | New tables for outreach contacts and profile cache |
| Dashboard | React 18 + Tailwind CSS | LinkedIn badges, contacts panel, enrichment display |
| MCP Server | TypeScript (@modelcontextprotocol/sdk) | 4 new tools for networking automation |

## Components

| Component | Responsibility | Dependencies |
|-----------|---------------|-------------|
| HttpMcpClient | JSON-RPC 2.0 over HTTP, retry, timeout, session management | WebClient, ObjectMapper |
| LinkedInJobProvider | Discovery via search_jobs, map to DiscoveredCompany | HttpMcpClient, DiscoveryProperties |
| LinkedInExtractor | Extract job details via MCP, map to RawJobData | HttpMcpClient, JobExtractorRegistry |
| LinkedInCompanyEnricher | Async company profile + posts enrichment | HttpMcpClient, CompanyRepository |
| LinkedInNetworkingService | Outreach workflow: find contacts, connect, message | HttpMcpClient, OutreachContactRepository |
| LinkedInProfileService | Profile lookup with 7-day cache | HttpMcpClient, ProfileCacheRepository |
| LinkedInRateLimiter | Token bucket per tool category | Configuration properties |
| LinkedInHealthIndicator | Session validity check, container health | HttpMcpClient |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         JobHunter Platform                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌──────────────┐   ┌──────────────┐   ┌───────────────────┐        │
│  │ CrawlScheduler│   │DiscoveryServ │   │ Admin Controller  │        │
│  └──────┬───────┘   └──────┬───────┘   └────────┬──────────┘        │
│         │                   │                     │                    │
│  ┌──────▼───────┐   ┌──────▼───────┐   ┌────────▼──────────┐        │
│  │  LinkedIn     │   │  LinkedIn    │   │   LinkedIn        │        │
│  │  Extractor    │   │  JobProvider │   │   CompanyEnricher │        │
│  └──────┬───────┘   └──────┬───────┘   └────────┬──────────┘        │
│         │                   │                     │                    │
│         │            ┌──────▼───────┐             │                    │
│         │            │  LinkedIn    │             │                    │
│         │            │  Networking  │             │                    │
│         │            │  Service     │             │                    │
│         │            └──────┬───────┘             │                    │
│         │                   │                     │                    │
│  ┌──────▼───────────────────▼─────────────────────▼──────────┐        │
│  │                  LinkedInRateLimiter                        │        │
│  └──────────────────────────┬────────────────────────────────┘        │
│                              │                                         │
│  ┌──────────────────────────▼────────────────────────────────┐        │
│  │                    HttpMcpClient                            │        │
│  │         (WebClient → JSON-RPC 2.0 over HTTP)               │        │
│  └──────────────────────────┬────────────────────────────────┘        │
│                              │                                         │
└──────────────────────────────┼─────────────────────────────────────────┘
                               │ HTTP POST :8000/mcp
┌──────────────────────────────▼────────────────────────────────────────┐
│                  linkedin-mcp (Docker Sidecar)                          │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  Python MCP Server (Patchright + Chromium)                   │       │
│  │  - search_jobs, get_person_profile, get_company_profile      │       │
│  │  - connect_with_person, send_message, search_people          │       │
│  └─────────────────────────────────────────────────────────────┘       │
│  Volume: ~/.linkedin-mcp → /app/.linkedin-mcp (browser profile)        │
└────────────────────────────────────────────────────────────────────────┘
```

**Description**: All LinkedIn operations route through `HttpMcpClient` which enforces rate limiting before forwarding JSON-RPC requests to the persistent Docker sidecar. The sidecar maintains a Chromium browser session authenticated to LinkedIn. Services are layered: discovery/extraction feed the existing filter/score pipeline, enrichment runs async post-discovery, networking is user-initiated via MCP tools only.

## Interfaces

### HttpMcpClient

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| callTool | String toolName, Map<String, Object> params | JsonNode | Send JSON-RPC 2.0 request to sidecar, parse response | McpClientException (timeout, connection refused, JSON-RPC error) |
| callToolAsync | String toolName, Map<String, Object> params | Mono\<JsonNode\> | Non-blocking version for async workflows | Same as callTool, wrapped in Mono.error |
| isSessionValid | — | boolean | Calls get_my_profile, returns true if no auth error | false on any error |
| initialize | — | void | Sends JSON-RPC initialize + notifications/initialized | McpClientException if handshake fails |

### LinkedInJobProvider (implements DiscoveryProvider)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| name | — | String "linkedin" | Provider identifier | — |
| discover | DiscoveryQuery query | List\<DiscoveredCompany\> | Calls search_jobs per keyword+location combo, extracts company + job URL | Returns empty list on failure (circuit breaker) |
| isHealthy | — | boolean | Checks config enabled + circuit breaker + session validity | — |
| getStats | — | DiscoveryProviderStats | Accumulated call metrics | — |

### LinkedInExtractor (implements JobExtractor)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| supportedTypes | — | Set\<AtsType\> {LINKEDIN} | Declares AtsType.LINKEDIN support | — |
| extract | CareerEndpoint endpoint | ExtractionResult | Calls search_jobs with endpoint URL/slug as search context, maps results to RawJobData | ExtractionResult.error on MCP failure |
| canExtract | CareerEndpoint endpoint | boolean | True if atsType==LINKEDIN and URL is valid LinkedIn jobs URL | — |

### LinkedInCompanyEnricher

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| enrich | Company company | CompletableFuture\<Void\> | Calls get_company_profile + get_company_posts, updates Company entity fields | Logs error, does not propagate (best-effort) |
| enrichBatch | List\<Company\> companies | CompletableFuture\<Void\> | Enriches up to 10 companies with rate-limited delays | Same as enrich |
| detectHiringSurge | UUID companyId | boolean | Checks recent post count for job-related posts, returns true if > threshold | false on error |

### LinkedInNetworkingService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| findContacts | UUID companyId, List\<String\> titleKeywords | List\<OutreachContact\> | Calls search_people + get_company_employees, persists contacts | McpClientException → empty list |
| connect | UUID contactId, String note | ConnectionResult | Calls connect_with_person, checks daily limit, updates contact status | DailyLimitExceededException, ContactNotFoundException |
| sendMessage | UUID contactId, String message | MessageResult | Calls send_message, checks cooldown, logs interaction | CooldownActiveException, NotConnectedException |
| getDailyConnectionsRemaining | — | int | Returns (daily_limit - connections_sent_today) | — |

### LinkedInProfileService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| getProfile | String linkedinUrl | ProfileData | Check cache (7-day TTL), call get_person_profile if miss | McpClientException → null |
| getSidebarProfiles | String linkedinUrl | List\<ProfileSummary\> | Calls get_sidebar_profiles for related people | Empty list on error |
| invalidateCache | String linkedinUrl | void | Remove cache entry for re-fetch | — |

### LinkedInRateLimiter

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| acquire | ToolCategory category | boolean | Try consume token from bucket, return false if empty | — |
| acquireOrWait | ToolCategory category, Duration maxWait | boolean | Block up to maxWait for token availability | false if timeout |
| getRemainingTokens | ToolCategory category | int | Current bucket level | — |

## Data Flow

### Job Discovery Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | DiscoveryScheduler | Triggers discovery run (every 6h for LinkedIn) | LinkedInJobProvider |
| 2 | LinkedInJobProvider | Builds DiscoveryQuery from profile.yaml keywords/locations | HttpMcpClient |
| 3 | HttpMcpClient | Sends search_jobs JSON-RPC to sidecar | linkedin-mcp container |
| 4 | linkedin-mcp | Scrapes LinkedIn search results via Chromium | HttpMcpClient (response) |
| 5 | LinkedInJobProvider | Parses response → List\<DiscoveredCompany\> | DiscoveryService |
| 6 | DiscoveryService | Normalizes company, creates/updates Company + CareerEndpoint (AtsType.LINKEDIN) | CompanyRepository |
| 7 | CrawlScheduler | Picks up new LINKEDIN endpoints for extraction | LinkedInExtractor |
| 8 | LinkedInExtractor | Calls search_jobs scoped to company, maps → RawJobData | CrawlService |
| 9 | CrawlService | Persists JobPosting, triggers filter pipeline (Language, Role, Location, YOE, Dedup) | FilterService |
| 10 | ScoringScheduler | Scores new jobs (MatchScore, OpportunityScore) | Dashboard |

### Outreach Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | MCP Tool (find_contacts) | User requests contacts at company via Claude | API Controller |
| 2 | LinkedInNetworkingService.findContacts | search_people filtered by title keywords | HttpMcpClient |
| 3 | HttpMcpClient | search_people + get_company_employees | linkedin-mcp |
| 4 | LinkedInNetworkingService | Persists OutreachContact entries, returns list | MCP Tool response |
| 5 | MCP Tool (connect_with) | User requests connection with note | API Controller |
| 6 | LinkedInNetworkingService.connect | Checks daily limit, calls connect_with_person | HttpMcpClient |
| 7 | HttpMcpClient | connect_with_person JSON-RPC | linkedin-mcp |
| 8 | LinkedInNetworkingService | Updates contact status to PENDING, logs timestamp | MCP Tool response |

### Profile Research Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | MCP Tool (research_person) | User requests profile research | API Controller |
| 2 | LinkedInProfileService.getProfile | Check ProfileCache (7-day TTL) | ProfileCacheRepository |
| 3 | LinkedInProfileService | Cache miss → call get_person_profile | HttpMcpClient |
| 4 | HttpMcpClient | get_person_profile with sections: experience, skills, posts | linkedin-mcp |
| 5 | LinkedInProfileService | Store in ProfileCache (JSONB), return structured data | MCP Tool response |

**Error Flows**:
- **Session expired**: HttpMcpClient detects auth error in JSON-RPC response → marks session invalid → LinkedInHealthIndicator reports unhealthy on /api/admin/health → all providers return empty until re-auth
- **Rate limit exceeded**: LinkedInRateLimiter returns false → caller either waits or skips → logged as warning → no request sent to sidecar
- **Container down**: WebClient connection refused → circuit breaker opens after 3 failures → 1h cooldown → health endpoint reports container unreachable
- **MCP tool error**: JSON-RPC error response → McpClientException with error code/message → service-layer catches and returns graceful fallback (empty list, error result)

## Data Model

### OutreachContact (new entity)

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, auto-generated |
| company_id | UUID | FK → company.id, NOT NULL |
| linkedin_url | VARCHAR(500) | UNIQUE, NOT NULL |
| person_name | VARCHAR(255) | NOT NULL |
| title | VARCHAR(500) | |
| connection_status | ENUM (NONE, PENDING, CONNECTED, DECLINED) | NOT NULL, default NONE |
| last_contacted_at | TIMESTAMP | |
| connection_sent_at | TIMESTAMP | |
| notes | TEXT | |
| created_at | TIMESTAMP | NOT NULL, auto |
| updated_at | TIMESTAMP | NOT NULL, auto |

### ProfileCache (new entity)

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, auto-generated |
| linkedin_url | VARCHAR(500) | UNIQUE, NOT NULL |
| profile_data | JSONB | NOT NULL |
| fetched_at | TIMESTAMP | NOT NULL |
| expires_at | TIMESTAMP | NOT NULL (fetched_at + 7 days) |
| created_at | TIMESTAMP | NOT NULL, auto |

### Company (extended columns)

| Field | Type | Constraints |
|-------|------|-------------|
| linkedin_url | VARCHAR(500) | NULLABLE |
| industry | VARCHAR(255) | NULLABLE |
| employee_count | INTEGER | NULLABLE |
| specialties | TEXT | NULLABLE (comma-separated) |
| recent_posts_summary | TEXT | NULLABLE |
| linkedin_enriched_at | TIMESTAMP | NULLABLE |

### JobPosting (extended columns for LinkedIn poster)

| Field | Type | Constraints |
|-------|------|-------------|
| poster_name | VARCHAR(255) | NULLABLE |
| poster_title | VARCHAR(500) | NULLABLE |
| poster_linkedin_url | VARCHAR(500) | NULLABLE |
| poster_avatar_url | VARCHAR(500) | NULLABLE |
| poster_contact_id | UUID | FK → outreach_contact.id, NULLABLE |

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| HTTP transport over stdio | WebClient → HTTP POST to /mcp | LinkedIn MCP needs persistent browser session; stdio spawn-per-call would kill browser state | Reuse McpSidecarClient (stdio) | HTTP adds network hop but enables persistent session + container isolation |
| Docker sidecar over in-process | Separate container with volume mount | Chromium isolation, memory limits, independent restart, cross-platform auth | Embed Patchright in JVM process | Extra container complexity but clean separation of concerns |
| Token bucket rate limiting | Per-category buckets (search: 20/hr, profile: 15/hr, action: 10/hr) | LinkedIn enforces undocumented limits; conservative budgets prevent account flags | Global single bucket | Per-category allows burst in reads while protecting writes |
| 7-day profile cache TTL | ProfileCache entity with JSONB + expiry | Avoid re-scraping same person repeatedly; profiles change slowly | In-memory cache (Caffeine) | DB cache survives restarts; slightly slower than memory but persistent |
| Async company enrichment | CompletableFuture, triggered post-discovery | Enrichment is non-critical and slow; shouldn't block discovery pipeline | Synchronous in discovery loop | May miss enrichment on failure but discovery stays fast |
| User-initiated outreach only | MCP tools expose connect/message; no automated mass outreach | LinkedIn ToS compliance; user controls all networking actions | Automated daily outreach scheduler | Safer but requires manual trigger; prevents account bans |
| LINKEDIN enum reuse | Use existing AtsType.LINKEDIN + new DiscoverySource.LINKEDIN | Enum values already exist (stubbed); no migration needed for enums | New separate enum | Consistent with existing pattern; CareerEndpoint.atsType=LINKEDIN triggers LinkedInExtractor |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| LinkedIn account restriction/ban | Complete loss of LinkedIn data source | Medium | Conservative rate limits (50 req/hr total), randomized delays (2-5s between calls), no automated outreach |
| Browser session expiry mid-crawl | Discovery/extraction returns empty until re-auth | High | Health check on /api/admin/health, session validity check before batch operations, alert in dashboard |
| LinkedIn MCP server instability (connect_with_person known issues) | Outreach features fail | Medium | Phase 4 marked experimental, graceful degradation, retry with exponential backoff (max 3 attempts) |
| Chromium OOM in Docker (2GB limit) | Container crash, temporary service loss | Low | mem_limit: 2G in compose, restart: unless-stopped, periodic health-based restart |
| LinkedIn HTML structure changes | MCP tools return parsing errors | Medium | Pin linkedin-mcp-server image tag, test with WireMock, fallback to cached data |
| Race condition in rate limiter | Exceed actual LinkedIn limits | Low | AtomicLong-based token bucket, synchronized refill, 20% safety margin on budgets |
| Profile volume mount permissions | Container can't read browser profile | Low | Document mount path + permissions in README, health check validates on startup |

## Test Plan

### Unit Tests

**HttpMcpClient**:
- Happy path: valid JSON-RPC response parsed correctly
- Timeout: WebClient times out → McpClientException
- Connection refused: container unreachable → McpClientException
- Invalid JSON response: malformed body → McpClientException
- Auth error in response: error code -32001 → session invalid flag set

**LinkedInJobProvider**:
- Mock HttpMcpClient responses → verify DiscoveredCompany mapping
- Empty results → empty list (no exception)
- Circuit breaker: 3 failures → isHealthy returns false
- Circuit breaker reset after cooldown

**LinkedInExtractor**:
- Mock search_jobs response → verify RawJobData mapping (title, location, description, applyUrl)
- canExtract: true for AtsType.LINKEDIN with valid URL
- canExtract: false for other AtsTypes or null URL

**LinkedInNetworkingService**:
- Daily limit enforcement: 5th connection in same day → DailyLimitExceededException
- Cooldown enforcement: message to same person within 7 days → CooldownActiveException
- Contact persistence: findContacts stores in DB

**LinkedInRateLimiter**:
- Token consumption and refill timing
- Category isolation (search bucket doesn't affect action bucket)
- Concurrent access safety

### Integration Tests

- **HttpMcpClient + WireMock**: Simulate linkedin-mcp HTTP responses, verify full JSON-RPC lifecycle (initialize → call → response)
- **LinkedInJobProvider + DB**: Verify discovery results persist via DiscoveryService integration
- **LinkedInExtractor + Filter pipeline**: LinkedIn jobs pass through Language/Role/Location/YOE filters correctly
- **OutreachContact CRUD**: Repository operations with Testcontainers PostgreSQL
- **ProfileCache expiry**: Verify expired entries trigger re-fetch

### End-to-End Tests

- LinkedIn jobs appear in dashboard after discovery run (manual, requires live container)
- Company enrichment populates industry/size in Company detail view
- MCP tool `find_contacts` returns structured contact list
- MCP tool `research_person` returns cached profile on second call

### Non-Functional Tests

- **Rate limiting**: Verify 50 requests/hour cap holds under concurrent load
- **Timeout**: HttpMcpClient respects 30s timeout, doesn't hang indefinitely
- **Memory**: Docker container stays under 2GB during sustained operation
- **Resilience**: Container restart mid-request → circuit breaker opens → auto-recovery after cooldown

## Configuration Schema

### application.yaml additions

```yaml
linkedin-mcp:
  enabled: ${LINKEDIN_MCP_ENABLED:false}
  base-url: ${LINKEDIN_MCP_URL:http://linkedin-mcp:8000}
  path: /mcp
  timeout-seconds: 30
  rate-limit:
    search-per-hour: 20
    profile-per-hour: 15
    action-per-hour: 10
    total-per-hour: 50
  circuit-breaker:
    failure-threshold: 3
    cooldown-minutes: 60
  enrichment:
    enabled: true
    batch-size: 10
    delay-between-ms: 3000

discovery:
  providers:
    linkedin:
      enabled: ${LINKEDIN_MCP_ENABLED:false}
      keywords: ["backend engineer", "Java developer", "Spring Boot"]
      locations: ["Germany", "Netherlands"]
      experience-level: "mid-senior"
      job-type: "full-time"
      crawl-frequency-hours: 6
```

### profile.yaml additions

```yaml
discovery:
  providers:
    linkedin:
      keywords: ["backend engineer", "Java developer", "Spring Boot", "Kotlin"]
      locations: ["Germany", "Netherlands", "remote"]
      experience-level: "mid-senior"
      job-type: "full-time"

networking:
  linkedin:
    daily-connection-limit: 5
    message-cooldown-days: 7
    target-titles:
      - "recruiter"
      - "hiring manager"
      - "engineering manager"
      - "head of engineering"
      - "talent acquisition"
```

## Docker Compose Changes

```yaml
  linkedin-mcp:
    image: stickerdaniel/linkedin-mcp-server:latest
    ports:
      - "8000:8000"
    environment:
      MCP_TRANSPORT: streamable-http
      MCP_PORT: 8000
    volumes:
      - ${HOME}/.linkedin-mcp:/app/.linkedin-mcp
    mem_limit: 2g
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8000/mcp"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 30s
```

API service gains:
```yaml
    environment:
      LINKEDIN_MCP_ENABLED: "true"
      LINKEDIN_MCP_URL: http://linkedin-mcp:8000
    depends_on:
      linkedin-mcp:
        condition: service_healthy
```

## DB Migration Plan (Liquibase)

### Changeset 1: outreach_contact table

```xml
<changeSet id="20240601-1-create-outreach-contact" author="jobhunter">
    <createTable tableName="outreach_contact">
        <column name="id" type="uuid" defaultValueComputed="gen_random_uuid()">
            <constraints primaryKey="true"/>
        </column>
        <column name="company_id" type="uuid">
            <constraints nullable="false" foreignKeyName="fk_outreach_company"
                         references="company(id)"/>
        </column>
        <column name="linkedin_url" type="varchar(500)">
            <constraints nullable="false" unique="true"/>
        </column>
        <column name="person_name" type="varchar(255)">
            <constraints nullable="false"/>
        </column>
        <column name="title" type="varchar(500)"/>
        <column name="connection_status" type="varchar(20)" defaultValue="NONE">
            <constraints nullable="false"/>
        </column>
        <column name="last_contacted_at" type="timestamp"/>
        <column name="connection_sent_at" type="timestamp"/>
        <column name="notes" type="text"/>
        <column name="created_at" type="timestamp" defaultValueComputed="now()">
            <constraints nullable="false"/>
        </column>
        <column name="updated_at" type="timestamp" defaultValueComputed="now()">
            <constraints nullable="false"/>
        </column>
    </createTable>
    <createIndex tableName="outreach_contact" indexName="idx_outreach_company">
        <column name="company_id"/>
    </createIndex>
</changeSet>
```

### Changeset 2: profile_cache table

```xml
<changeSet id="20240601-2-create-profile-cache" author="jobhunter">
    <createTable tableName="profile_cache">
        <column name="id" type="uuid" defaultValueComputed="gen_random_uuid()">
            <constraints primaryKey="true"/>
        </column>
        <column name="linkedin_url" type="varchar(500)">
            <constraints nullable="false" unique="true"/>
        </column>
        <column name="profile_data" type="jsonb">
            <constraints nullable="false"/>
        </column>
        <column name="fetched_at" type="timestamp">
            <constraints nullable="false"/>
        </column>
        <column name="expires_at" type="timestamp">
            <constraints nullable="false"/>
        </column>
        <column name="created_at" type="timestamp" defaultValueComputed="now()">
            <constraints nullable="false"/>
        </column>
    </createTable>
    <createIndex tableName="profile_cache" indexName="idx_profile_cache_url">
        <column name="linkedin_url"/>
    </createIndex>
    <createIndex tableName="profile_cache" indexName="idx_profile_cache_expires">
        <column name="expires_at"/>
    </createIndex>
</changeSet>
```

### Changeset 3: Company LinkedIn columns

```xml
<changeSet id="20240601-3-company-linkedin-columns" author="jobhunter">
    <addColumn tableName="company">
        <column name="linkedin_url" type="varchar(500)"/>
        <column name="industry" type="varchar(255)"/>
        <column name="employee_count" type="integer"/>
        <column name="specialties" type="text"/>
        <column name="recent_posts_summary" type="text"/>
        <column name="linkedin_enriched_at" type="timestamp"/>
    </addColumn>
</changeSet>
```

### Changeset 4: JobPosting poster columns

```xml
<changeSet id="20240601-4-job-posting-poster-columns" author="jobhunter">
    <addColumn tableName="job_posting">
        <column name="poster_name" type="varchar(255)"/>
        <column name="poster_title" type="varchar(500)"/>
        <column name="poster_linkedin_url" type="varchar(500)"/>
        <column name="poster_avatar_url" type="varchar(500)"/>
        <column name="poster_contact_id" type="uuid">
            <constraints foreignKeyName="fk_job_poster_contact"
                         references="outreach_contact(id)"/>
        </column>
    </addColumn>
</changeSet>
```

## Rate Limiting Design

```
┌─────────────────────────────────────────────┐
│           LinkedInRateLimiter                 │
├─────────────────────────────────────────────┤
│                                               │
│  ┌─────────────────┐  ┌─────────────────┐   │
│  │ SEARCH bucket   │  │ PROFILE bucket  │   │
│  │ 20 tokens/hour  │  │ 15 tokens/hour  │   │
│  │ refill: 1/3min  │  │ refill: 1/4min  │   │
│  └─────────────────┘  └─────────────────┘   │
│                                               │
│  ┌─────────────────┐  ┌─────────────────┐   │
│  │ ACTION bucket   │  │ GLOBAL bucket   │   │
│  │ 10 tokens/hour  │  │ 50 tokens/hour  │   │
│  │ refill: 1/6min  │  │ refill: 1/72s   │   │
│  └─────────────────┘  └─────────────────┘   │
│                                               │
│  Every request must pass BOTH category        │
│  bucket AND global bucket.                    │
└─────────────────────────────────────────────┘
```

**Tool → Category mapping**:
| Tool | Category |
|------|----------|
| search_jobs | SEARCH |
| search_people | SEARCH |
| search_companies | SEARCH |
| search_conversations | SEARCH |
| get_person_profile | PROFILE |
| get_company_profile | PROFILE |
| get_company_posts | PROFILE |
| get_company_employees | PROFILE |
| get_sidebar_profiles | PROFILE |
| get_my_profile | PROFILE |
| get_inbox | PROFILE |
| get_conversation | PROFILE |
| connect_with_person | ACTION |
| send_message | ACTION |

**Randomized delay**: After each successful request, sleep random(2000, 5000)ms before releasing for next request. Simulates human browsing pattern.

## New MCP Tools (jobhunter-mcp)

### find_contacts

```typescript
const inputSchema = z.object({
  company_name: z.string().describe('Company name to search contacts at'),
  title_keywords: z.array(z.string()).optional()
    .describe('Filter by title keywords (default: recruiter, hiring manager)'),
  limit: z.number().optional().default(5)
    .describe('Max contacts to return'),
});

// Output: List of contacts with name, title, LinkedIn URL, connection status
```

### connect_with

```typescript
const inputSchema = z.object({
  contact_id: z.string().describe('UUID of OutreachContact to connect with'),
  note: z.string().max(300)
    .describe('Connection request note (max 300 chars)'),
});

// Output: Connection result (sent, daily_limit_reached, already_connected)
```

### send_linkedin_message

```typescript
const inputSchema = z.object({
  contact_id: z.string().describe('UUID of OutreachContact to message'),
  message: z.string().max(2000)
    .describe('Message text (max 2000 chars)'),
});

// Output: Message result (sent, cooldown_active, not_connected)
```

### research_person

```typescript
const inputSchema = z.object({
  linkedin_url: z.string().optional()
    .describe('LinkedIn profile URL'),
  name: z.string().optional()
    .describe('Person name (used with company for search)'),
  company: z.string().optional()
    .describe('Company name (used with name for search)'),
});

// Output: Structured profile: name, title, company, experience[], skills[], recent_posts[]
```

All 4 tools call the JobHunter API backend (`/api/linkedin/*` endpoints), which delegates to the respective services.

## Dashboard UI Changes

### JobCard LinkedIn Badge
- Source indicator: blue LinkedIn icon next to job source when `source === 'LINKEDIN'`
- Styling: `bg-blue-600/20 text-blue-400` badge, same pattern as existing ATS badges

### JobCard - Job Poster Section (LinkedIn jobs only)
- Display: poster avatar (if available), name, title, LinkedIn URL
- Data source: extracted during crawl (LinkedIn job metadata includes poster info)
- "Connect" button: visible when connection_status is NONE
  - Click → confirmation dialog: "Send connection request to {name}? ({daily_remaining} remaining today)"
  - On confirm → POST /api/linkedin/contacts/{id}/connect with optional note textarea
  - Success → button changes to "Pending" (disabled, yellow)
  - Daily limit reached → toast error "Daily limit reached (5/5)"
- "Message" button: visible when connection_status is CONNECTED
  - Click → modal with textarea (max 2000 chars) + "Send" button
  - On confirm → POST /api/linkedin/contacts/{id}/message
  - Cooldown active → button disabled with tooltip "Cooldown: message again in {days}d"
- Status badge next to poster name: NONE (no badge), PENDING (yellow dot), CONNECTED (green dot)

### Company Detail - LinkedIn Insights Section
- New card below existing company info
- Shows: industry, employee count, specialties, recent activity summary
- "Contacts found" count with link to contacts panel
- "Last enriched" timestamp
- "Enrich now" button (calls POST /api/linkedin/enrich/{companyId})

### Contacts Panel (Company Detail sub-section)
- Table: Name, Title, Connection Status, Last Contacted
- Status badges: NONE (gray), PENDING (yellow), CONNECTED (green), DECLINED (red)
- Same Connect/Message buttons as JobCard poster section (reusable component)

### Health Page Addition
- LinkedIn MCP section: session status (valid/expired), container health, rate limit usage (tokens remaining per bucket)

## Error Handling and Resilience

### Circuit Breaker (per-service)

```
State Machine:
  CLOSED → (3 consecutive failures) → OPEN
  OPEN → (60 min cooldown elapsed) → HALF_OPEN
  HALF_OPEN → (1 success) → CLOSED
  HALF_OPEN → (1 failure) → OPEN
```

Each service (Provider, Extractor, Enricher, Networking) has independent circuit breaker state. Shared `HttpMcpClient` does NOT have its own breaker (avoids cascading one service's failures to all).

### Session Expiry Detection

LinkedIn MCP returns specific error patterns when session expires:
- JSON-RPC error code: `-32001` (authentication required)
- Content contains "login" or "auth" redirect indicators

On detection:
1. `HttpMcpClient` sets `sessionValid = false`
2. All `isHealthy()` checks return false immediately
3. Health endpoint (`/api/admin/health`) reports `LINKEDIN_SESSION_EXPIRED`
4. Dashboard Health page shows red indicator with re-auth instructions
5. Recovery: user runs `uvx linkedin-scraper-mcp@latest --login` on host, container auto-detects new session via volume mount

### Retry Strategy

| Error Type | Retry | Backoff | Max Attempts |
|------------|-------|---------|--------------|
| Timeout | Yes | Exponential (2s, 4s, 8s) | 3 |
| Connection refused | No | — | 1 (circuit breaker handles) |
| Rate limit (429-like) | Yes | Fixed 60s | 2 |
| Auth error | No | — | 0 (session invalid) |
| JSON parse error | No | — | 1 |
| Tool-specific error | Yes | Linear (5s) | 2 |

## Interface Contracts (Java)

### HttpMcpClient

```java
package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface HttpMcpClient {

    JsonNode callTool(String toolName, Map<String, Object> params);

    Mono<JsonNode> callToolAsync(String toolName, Map<String, Object> params);

    boolean isSessionValid();
}
```

### LinkedInRateLimiter

```java
package dev.jobhunter.linkedin;

import java.time.Duration;

public interface LinkedInRateLimiter {

    boolean acquire(ToolCategory category);

    boolean acquireOrWait(ToolCategory category, Duration maxWait);

    int getRemainingTokens(ToolCategory category);

    enum ToolCategory {
        SEARCH, PROFILE, ACTION
    }
}
```

### LinkedInCompanyEnricher

```java
package dev.jobhunter.linkedin;

import dev.jobhunter.model.Company;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface LinkedInCompanyEnricher {

    CompletableFuture<Void> enrich(Company company);

    CompletableFuture<Void> enrichBatch(List<Company> companies);

    boolean detectHiringSurge(UUID companyId);
}
```

### LinkedInNetworkingService

```java
package dev.jobhunter.linkedin;

import java.util.List;
import java.util.UUID;

public interface LinkedInNetworkingService {

    List<OutreachContact> findContacts(UUID companyId, List<String> titleKeywords);

    ConnectionResult connect(UUID contactId, String note);

    MessageResult sendMessage(UUID contactId, String message);

    int getDailyConnectionsRemaining();

    record ConnectionResult(Status status, String message) {
        enum Status { SENT, DAILY_LIMIT_REACHED, ALREADY_CONNECTED, FAILED }
    }

    record MessageResult(Status status, String message) {
        enum Status { SENT, COOLDOWN_ACTIVE, NOT_CONNECTED, FAILED }
    }
}
```

### LinkedInProfileService

```java
package dev.jobhunter.linkedin;

import java.util.List;

public interface LinkedInProfileService {

    ProfileData getProfile(String linkedinUrl);

    List<ProfileSummary> getSidebarProfiles(String linkedinUrl);

    void invalidateCache(String linkedinUrl);

    record ProfileData(
        String name, String headline, String company,
        List<Experience> experience, List<String> skills,
        List<PostSummary> recentPosts
    ) {}

    record Experience(String title, String company, String duration) {}
    record PostSummary(String text, String date, int reactions) {}
    record ProfileSummary(String name, String title, String linkedinUrl) {}
}
```

### LinkedIn REST Controller (new endpoints for MCP tools)

```java
package dev.jobhunter.controller;

// POST /api/linkedin/contacts/search     → findContacts
// POST /api/linkedin/contacts/{id}/connect → connect
// POST /api/linkedin/contacts/{id}/message → sendMessage
// GET  /api/linkedin/profile?url=...      → getProfile
// POST /api/linkedin/enrich/{companyId}   → enrich company
// GET  /api/linkedin/health               → session + rate limit status
```

## Phase-to-Architecture Mapping

| Phase | Components Created | Config Changes | DB Migrations |
|-------|-------------------|----------------|---------------|
| 1: Infrastructure | HttpMcpClient, LinkedInRateLimiter, LinkedInHealthIndicator | linkedin-mcp section in application.yaml, docker-compose service | None |
| 2: Job Discovery | LinkedInJobProvider, LinkedInExtractor | discovery.providers.linkedin in profile.yaml | None (enums exist) |
| 3: Company Enrichment | LinkedInCompanyEnricher | enrichment config | Changeset 3 (Company columns) |
| 4: Networking | LinkedInNetworkingService, OutreachContact entity | networking section in profile.yaml | Changeset 1 (outreach_contact) |
| 5: Profile Intelligence | LinkedInProfileService, ProfileCache entity | — | Changeset 2 (profile_cache) |
| 6: Dashboard + MCP | 4 new MCP tools, REST endpoints, UI components | — | None |
