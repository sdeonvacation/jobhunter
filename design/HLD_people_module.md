# HLD: People Module (Phases 1-5)

## Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Java 21 | API extensions in existing Spring Boot app |
| Framework | Spring Boot 3.3.5 | Service layer, REST controllers, DI |
| Database | PostgreSQL 16 | JSONB for flexible profile/tech-stack data |
| Migrations | Liquibase | Schema changes for new tables + column extensions |
| Scheduling | Quartz | Contact discovery scheduler with quota management |
| Frontend | React 18 + Vite + Tailwind | People page, contact detail, job card enhancements |
| LinkedIn | Existing LinkedIn MCP | People search, profile fetch (via HttpMcpClient) |
| Scoring | profile.yaml config | All weights/thresholds externalized |

## Components

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| PosterExtractionService | Extract recruiter/HM info from crawled job pages per ATS type | PosterExtractorRegistry, OutreachContactRepository |
| PosterExtractor (interface) | Per-ATS extraction of poster name/title/URL from raw HTML/JSON | None (pure parsing) |
| PosterExtractorRegistry | Auto-registers PosterExtractor impls via Spring scan, selects by AtsType | PosterExtractor implementations |
| ContactPriorityScorer | Compute interviewGenerationWeight + warmthScore → contactPriorityScore | PersonalProfileLoader, ProfileCache |
| ContactDiscoveryService | Orchestrate LinkedIn search for companies lacking posters | LinkedInNetworkingService, LinkedInRateLimiter |
| ContactDiscoveryScheduler | Daily/weekly scheduling with quota and priority ordering | ContactDiscoveryService, CompanyRepository |
| RelationshipService | Manage full relationship aggregate: state machine, events, ghost detection | RelationshipRepository, RelationshipEventRepository |
| PeopleController | REST API for people/contacts/relationships | All services above |
| People (dashboard page) | CRM view: contacts grouped by status, sortable by priority | API client |

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         DASHBOARD (React)                            │
│  ┌──────────┐  ┌────────────────┐  ┌──────────────┐  ┌──────────┐ │
│  │ People   │  │ Contact Detail │  │ Job Cards    │  │ Company  │ │
│  │ Page     │  │ Panel          │  │ (enhanced)   │  │ Contacts │ │
│  └────┬─────┘  └───────┬────────┘  └──────┬───────┘  └────┬─────┘ │
└───────┼─────────────────┼──────────────────┼───────────────┼───────┘
        │                 │                  │               │
────────┼─────────────────┼──────────────────┼───────────────┼────────
        ▼                 ▼                  ▼               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       REST API LAYER                                 │
│  PeopleController: /api/people/*                                    │
│  LinkedInController: /api/linkedin/* (existing, extended)           │
└───────┬───────────────────────────────────┬─────────────────────────┘
        │                                   │
        ▼                                   ▼
┌───────────────────────┐   ┌──────────────────────────────────────┐
│  RELATIONSHIP LAYER   │   │         DISCOVERY LAYER              │
│                       │   │                                      │
│  RelationshipService  │   │  PosterExtractionService             │
│  RelationshipEvent    │   │    └── PosterExtractor (per ATS)     │
│    Service            │   │  ContactDiscoveryService             │
│  OutreachMessage      │   │    └── LinkedInNetworkingService     │
│    (entity only P2)   │   │  ContactDiscoveryScheduler           │
└───────┬───────────────┘   └───────────────┬──────────────────────┘
        │                                   │
        ▼                                   ▼
┌───────────────────────┐   ┌──────────────────────────────────────┐
│    SCORING LAYER      │   │       LINKEDIN INTEGRATION           │
│                       │   │       (existing, reused)             │
│  ContactPriority      │   │  HttpMcpClient                      │
│    Scorer             │   │  LinkedInRateLimiter                 │
│                       │   │  LinkedInProfileService              │
└───────┬───────────────┘   └──────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      DATA LAYER (PostgreSQL)                         │
│                                                                     │
│  outreach_contact (extended)  │  relationship  │  relationship_event│
│  outreach_message             │  contact_discovery_run              │
│  job_posting (poster fields)  │  application (interview_source)     │
└─────────────────────────────────────────────────────────────────────┘
```

**Description**: Two layers drive the module: (1) Discovery — poster extraction runs post-crawl as a hook in CrawlService; LinkedIn search runs on a Quartz schedule for companies without posters. (2) Relationship — event-sourced with a materialized status field on the Relationship entity for fast queries. ContactPriorityScorer sits between discovery and display, computing rankings from profile data + configurable weights.

## Interfaces

### PosterExtractor (Interface)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| extract | `(String rawHtml, Map<String,Object> rawJson)` | `Optional<PosterInfo>` | Parse poster name/title/linkedinUrl from ATS page content | Returns empty if not parseable |
| supports | `(AtsType type)` | `boolean` | Whether this extractor handles given ATS type | None |

```java
public interface PosterExtractor {
    Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson);
    boolean supports(AtsType type);
}

public record PosterInfo(String name, String title, String linkedinUrl, String avatarUrl) {}
```

### PosterExtractorRegistry

Mirrors existing pattern (auto-registration via Spring component scan). Selects the correct extractor for an ATS type.

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| getExtractor | `(AtsType type)` | `Optional<PosterExtractor>` | Find registered extractor supporting this ATS type | Returns empty if no extractor supports type |
| getSupportedTypes | `()` | `Set<AtsType>` | All ATS types with registered extractors | None |

```java
@Component
public class PosterExtractorRegistry {
    private final Map<AtsType, PosterExtractor> extractors;

    public PosterExtractorRegistry(List<PosterExtractor> allExtractors) {
        this.extractors = allExtractors.stream()
            .flatMap(e -> Arrays.stream(AtsType.values())
                .filter(e::supports)
                .map(type -> Map.entry(type, e)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Optional<PosterExtractor> getExtractor(AtsType type) {
        return Optional.ofNullable(extractors.get(type));
    }

    public Set<AtsType> getSupportedTypes() {
        return extractors.keySet();
    }
}
```

### PosterExtractionService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| extractAndLink | `(JobPosting job, String rawHtml, Map<String,Object> rawJson)` | `Optional<OutreachContact>` | Run matching extractor, upsert OutreachContact, set posterContactId on job | Logs extraction failures, never throws |
| getExtractionStats | `()` | `Map<AtsType, ExtractionRate>` | Success rate per ATS type | None |

### ContactPriorityScorer

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| score | `(OutreachContact contact)` | `ContactScore` | Compute all three scores: interviewGenerationWeight, warmthScore, contactPriorityScore | None |
| scoreBatch | `(List<OutreachContact> contacts)` | `List<ContactScore>` | Batch scoring for efficiency | None |

```java
public record ContactScore(
    UUID contactId,
    int interviewGenerationWeight,  // 0-100 based on seniority role
    int warmthScore,                // 0-100 based on profile similarity
    int contactPriorityScore        // 0-100 composite
) {}
```

#### Warmth Score Data Sources

Each warmth factor maps to a concrete data source:

| Factor | Weight | Data Source | Computation |
|--------|--------|-------------|-------------|
| Shared employer | 30% | `ProfileCache.profileData` → `experience[].company` vs `profile.yaml` → user's experience companies | Any overlap in company names (normalized) → full points |
| Same country/migration | 25% | `OutreachContact.location` or `ProfileCache.profileData` → location vs `profile.yaml` → `preferences.locations` | Country match or matching migration path (e.g., both relocated to Germany) |
| Same university | 15% | `ProfileCache.profileData` → `education[].school` vs `profile.yaml` → user's education | Any overlap → full points |
| Same tech stack | 15% | `OutreachContact.techStack` (JSONB) or `ProfileCache.profileData` → `skills[]` vs `profile.yaml` → `skills[].name` | Jaccard similarity on skill sets, scaled 0-100 |
| Mutual connections | 15% | LinkedIn MCP `find_contacts` response includes mutual connection count (if available) | > 5 mutuals → full points, 1-5 → proportional, 0 → 0 |

**Fallback**: If `ProfileCache` is empty/expired for a contact, `warmthScore = 0`. The `contactPriorityScore` then relies solely on `interviewGenerationWeight` (effectively: `0.6 * IGW + 0.4 * 0 = 0.6 * IGW`). Warmth becomes meaningful only after `research_person` enriches the profile cache.

### ContactDiscoveryService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| discoverForCompany | `(UUID companyId, List<String> titleKeywords)` | `ContactDiscoveryRun` | Call LinkedIn MCP, save contacts, record run | Rate limit → returns run with 0 contacts |
| discoverTopPriority | `(int maxCompanies)` | `List<ContactDiscoveryRun>` | Pick top N companies by priorityScore, run discovery | Respects shared quota |
| getDiscoveryRuns | `(UUID companyId)` | `List<ContactDiscoveryRun>` | Audit log for company | None |

### RelationshipService

Manages the full Relationship aggregate: status transitions, event recording, ghost detection. One service per aggregate root.

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| getOrCreate | `(UUID contactId)` | `Relationship` | Create DISCOVERED relationship if none exists | Contact not found → IllegalArgumentException |
| recordEvent | `(UUID relationshipId, EventType type, Map<String,Object> metadata)` | `RelationshipEvent` | Append event, recompute status via state machine | Invalid transition → log warning, still record |
| getByStatus | `(RelationshipStatus status, Pageable page)` | `Page<Relationship>` | Query by materialized status | None |
| getEvents | `(UUID relationshipId)` | `List<RelationshipEvent>` | Full event timeline for relationship | None |
| detectGhosting | `(int thresholdDays)` | `int` | Auto-transition CONTACTED→GHOSTED after N days no reply, records GHOSTED_AUTO event | Idempotent |
| recomputeStatus | `(UUID relationshipId)` | `RelationshipStatus` | Rebuild status from events (reconciliation/recovery) | None |

### PeopleController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| GET /api/people | `?status,company,seniority,sort,page,size` | `Page<ContactDto>` | List contacts with relationship + score | None |
| GET /api/people/{id} | UUID | `ContactDetailDto` | Full contact: profile, events, messages, linked jobs | 404 if not found |
| GET /api/people/{id}/events | UUID | `List<RelationshipEventDto>` | Event timeline | 404 |
| POST /api/people/{id}/events | `{eventType, metadata}` | `RelationshipEventDto` | Manual event recording | 400 on invalid type |
| GET /api/people/stats | - | `PeopleStatsDto` | Counts by status, avg score, discovery rates | None |
| POST /api/people/discover/{companyId} | UUID | `ContactDiscoveryRunDto` | Trigger manual discovery for company | 429 if rate limited |
| GET /api/people/discovery-runs | `?companyId` | `List<ContactDiscoveryRunDto>` | Audit trail | None |
| GET /api/companies/{id}/contacts | UUID | `List<ContactDto>` | Contacts for company with scores | 404 |

## Data Flow

### Poster Extraction Flow (runs during crawl)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | CrawlService | Crawls endpoint, gets FetchResult with raw HTML/JSON | PosterExtractionService |
| 2 | PosterExtractionService | Selects PosterExtractor for ATS type | PosterExtractor impl |
| 3 | PosterExtractor | Parses poster name/title/linkedinUrl from content | Back to service |
| 4 | PosterExtractionService | Upserts OutreachContact (dedup by linkedinUrl) | OutreachContactRepository |
| 5 | PosterExtractionService | Sets `posterContactId` FK on JobPosting | JobPostingRepository |
| 6 | ContactPriorityScorer | Scores new contact (async or deferred to batch) | OutreachContact updated |

### LinkedIn Discovery Flow (scheduled)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | ContactDiscoveryScheduler | Fires on cron, checks remaining LinkedIn quota | ContactDiscoveryService |
| 2 | ContactDiscoveryService | Queries companies: ACTIVE, no poster contacts, by priorityScore DESC | CompanyRepository |
| 3 | ContactDiscoveryService | Calls LinkedInNetworkingService.findContacts per company | LinkedInNetworkingService |
| 4 | LinkedInNetworkingService | Calls MCP search_people, parses results, saves contacts | OutreachContactRepository |
| 5 | ContactDiscoveryService | Records ContactDiscoveryRun audit entry | ContactDiscoveryRunRepository |
| 6 | ContactPriorityScorer | Batch-scores newly discovered contacts | Score persisted to contact |

### Relationship Event Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Caller (controller/scheduler) | Calls RelationshipService.recordEvent | RelationshipService |
| 2 | RelationshipService | Creates RelationshipEvent record | RelationshipEventRepository |
| 3 | RelationshipService | Derives new status from event type (state machine) | Updates Relationship.status |
| 4 | RelationshipService | Updates lastContactAt/lastReplyAt if applicable | Relationship saved |

**Error Flows**:
- Poster extraction fails silently (logs warning, job saved without poster). Does not block crawl.
- LinkedIn rate limit → ContactDiscoveryService returns early, records run with `contactsFound=0`. Scheduler moves to next cycle.
- Invalid relationship event type → HTTP 400 from controller. Service logs and rejects.
- Ghost detection is idempotent: running multiple times does not create duplicate events.

## Data Model

### New Entities

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| Relationship | `id: UUID, contactId: UUID (FK), status: RelationshipStatus, lastContactAt: timestamp, lastReplyAt: timestamp, responseRate: double, referralRequested: boolean, referred: boolean, interviewObtained: boolean, referredByContactId: UUID (FK nullable), notes: text, createdAt, updatedAt` | OutreachContact (many-to-one), self-ref via referredByContactId | Unique on contactId (one relationship per contact) |
| RelationshipEvent | `id: UUID, relationshipId: UUID (FK), eventType: EventType, occurredAt: timestamp, metadata: JSONB, createdAt` | Relationship (many-to-one) | Index on (relationshipId, occurredAt DESC) |
| OutreachMessage | `id: UUID, contactId: UUID (FK), direction: Direction (IN/OUT), channel: Channel (LINKEDIN/EMAIL), messageType: MessageType, content: text, sentAt: timestamp, replied: boolean, repliedAt: timestamp, createdAt` | OutreachContact (many-to-one) | Index on (contactId, sentAt DESC) |
| ContactDiscoveryRun | `id: UUID, companyId: UUID (FK), source: DiscoverySource, contactsFound: int, contactsNew: int, runAt: timestamp` | Company (many-to-one) | Index on (companyId, runAt DESC) |

### Extended Entities

| Entity | New Fields | Purpose |
|--------|-----------|---------|
| OutreachContact | `seniority: Seniority (enum), discoveredVia: ContactDiscoverySource (enum), location: String, techStack: JSONB (String[]), interviewGenerationWeight: int, warmthScore: int, contactPriorityScore: int` | Priority scoring + filtering |
| Application | `interviewSource: InterviewSource (enum, nullable)` | Track how interviews are obtained |

### Enums

| Enum | Values |
|------|--------|
| Seniority | RECRUITER, MANAGER, DIRECTOR, STAFF, SENIOR, IC |
| ContactDiscoverySource | JOB_POSTER, LINKEDIN_SEARCH, MANUAL |
| RelationshipStatus | DISCOVERED, CONTACTED, REPLIED, ENGAGED, REFERRED, INTERVIEW_OBTAINED, GHOSTED, COLD |
| EventType | CONTACT_DISCOVERED, MESSAGE_SENT, REPLIED, CALL_BOOKED, REFERRAL_REQUESTED, REFERRAL_GIVEN, INTERVIEW_OBTAINED, GHOSTED_AUTO, STATUS_OVERRIDE |
| Direction | IN, OUT |
| Channel | LINKEDIN, EMAIL |
| MessageType | INFO_CHAT, TECH_DISCUSSION, REFERRAL, FOLLOWUP, RECRUITER |
| InterviewSource | APPLICATION, RECRUITER, REFERRAL, NETWORKING, EVENT |

### Liquibase Migration Plan

**Changeset 1**: Extend `outreach_contact` table
```sql
ALTER TABLE outreach_contact ADD COLUMN seniority VARCHAR(20);
ALTER TABLE outreach_contact ADD COLUMN discovered_via VARCHAR(20) DEFAULT 'MANUAL';
ALTER TABLE outreach_contact ADD COLUMN location VARCHAR(255);
ALTER TABLE outreach_contact ADD COLUMN tech_stack JSONB;
ALTER TABLE outreach_contact ADD COLUMN interview_generation_weight INTEGER DEFAULT 0;
ALTER TABLE outreach_contact ADD COLUMN warmth_score INTEGER DEFAULT 0;
ALTER TABLE outreach_contact ADD COLUMN contact_priority_score INTEGER DEFAULT 0;
```

**Changeset 2**: Create `relationship` table
```sql
CREATE TABLE relationship (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id UUID NOT NULL UNIQUE REFERENCES outreach_contact(id),
    status VARCHAR(30) NOT NULL DEFAULT 'DISCOVERED',
    last_contact_at TIMESTAMP,
    last_reply_at TIMESTAMP,
    response_rate DOUBLE PRECISION DEFAULT 0.0,
    referral_requested BOOLEAN DEFAULT FALSE,
    referred BOOLEAN DEFAULT FALSE,
    interview_obtained BOOLEAN DEFAULT FALSE,
    referred_by_contact_id UUID REFERENCES outreach_contact(id),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_relationship_status ON relationship(status);
CREATE INDEX idx_relationship_contact ON relationship(contact_id);
```

**Changeset 3**: Create `relationship_event` table
```sql
CREATE TABLE relationship_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id UUID NOT NULL REFERENCES relationship(id),
    event_type VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW(),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rel_event_relationship ON relationship_event(relationship_id, occurred_at DESC);
```

**Changeset 4**: Create `outreach_message` table
```sql
CREATE TABLE outreach_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id UUID NOT NULL REFERENCES outreach_contact(id),
    direction VARCHAR(5) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content TEXT,
    sent_at TIMESTAMP NOT NULL DEFAULT NOW(),
    replied BOOLEAN DEFAULT FALSE,
    replied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outreach_msg_contact ON outreach_message(contact_id, sent_at DESC);
```

**Changeset 5**: Create `contact_discovery_run` table
```sql
CREATE TABLE contact_discovery_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES company(id),
    source VARCHAR(20) NOT NULL,
    contacts_found INTEGER NOT NULL DEFAULT 0,
    contacts_new INTEGER NOT NULL DEFAULT 0,
    run_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_discovery_run_company ON contact_discovery_run(company_id, run_at DESC);
```

**Changeset 6**: Add `interview_source` to `application`
```sql
ALTER TABLE application ADD COLUMN interview_source VARCHAR(20);
```

### Migration of Existing Data

Existing `outreach_contact` records get:
- `discovered_via = 'MANUAL'` (backfill default)
- `seniority` inferred from `title` field where possible (regex: "recruiter" → RECRUITER, "manager" → MANAGER, etc.)
- Auto-create `Relationship` with status `DISCOVERED` for contacts with `connectionStatus = NONE`, `CONTACTED` for those with `PENDING/CONNECTED`
- Score computation runs as batch after migration

**Note on poster_contact_id**: The `job_posting.poster_contact_id` column and FK constraint already exist (added in `003-linkedin-integration.sql`). No new migration needed — `PosterExtractionService` simply populates the existing nullable FK during crawl.

## Decisions

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| Poster extraction as post-crawl hook | Inject into CrawlService after job persistence | Runs at zero API cost; content already fetched; poster data tied to specific job | Separate scheduler re-fetching pages | Couples to crawl flow; extraction failure must not block crawl |
| Scores stored on OutreachContact | Denormalize scores directly on entity | Fast sort/filter in queries without joins; recomputed on profile fetch or schedule | Separate score table (like MatchScore) | Stale if not recomputed; accepted since contacts change rarely |
| Event-sourced relationships with materialized status | Store events + derived status field | Events enable timeline/audit; materialized status enables fast filtering (WHERE status = X) | Pure event sourcing (derive on read) | Dual-write risk if status computation has bugs; mitigated by recompute-from-events endpoint |
| Single Relationship per contact | UNIQUE constraint on contact_id | Simplifies queries; one contact = one relationship lifecycle | Multiple relationships (per job) | If same contact relevant to multiple jobs, still one relationship |
| PosterExtractor per ATS type | Strategy pattern, auto-registered via Spring scan | Mirrors existing FetchStrategy pattern; each ATS has different HTML structure | Single regex-based extractor | More classes; worth it for accuracy per ATS |
| Discovery scheduler quota sharing | Check LinkedInRateLimiter before each search | Prevents automated discovery from starving manual MCP tool usage | Separate quota pools | Single pool simpler; manual usage is rare during automated cycles |
| Seniority from title (heuristic) | Regex match on title string | No API call needed; reasonable accuracy for common titles | AI classification | AI adds latency and cost for low-value classification |
| New package: `dev.jobhunter.people` | Dedicated package for People module entities/services | Clean boundary; doesn't pollute existing linkedin/ or model/ packages | Extend linkedin/ package | people/ is a domain concept above linkedin networking |
| YAGNI on generic interfaces | Concrete classes for Scorers, Queue, ContextAssembler; only AiTask gets interface | Existing scorers are all concrete with different signatures; generic interface serves only 2 new impls; extract when second impl proves need | Generic Scorer\<T\>, PrioritizedQueue\<T\>, ContextAssembler\<T\> | Loses potential uniformity; accepted because adapters for existing scorers would be pure boilerplate |
| Single RelationshipService (no EventService split) | One service manages full aggregate (status + events + ghost detection) | Aggregate root pattern: one service per aggregate prevents split-brain state management | Separate RelationshipService + RelationshipEventService | Slightly larger class; acceptable for aggregate consistency |

## Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| ATS poster extraction yields low hit rate | Many jobs without auto-discovered contacts | Medium | Track extraction rate per ATS; focus effort on high-volume ATS types (Greenhouse, Lever, Ashby account for ~60% of endpoints) |
| LinkedIn rate limits exhausted by discovery scheduler | Manual MCP tools blocked for the day | Medium | Scheduler checks remaining budget first; configurable daily cap for automated discovery (default: 50% of daily quota) |
| Warmth score inaccuracy | Misleading priority rankings | Medium | Make warmth optional (0 if no profile data); surface raw factors in UI; user can override sort |
| Relationship status drift | Status diverges from reality (user interacts outside system) | High | STATUS_OVERRIDE event type; periodic ghost detection; "last known" semantics in UI |
| Poster data GDPR exposure | Storing personal data without consent | Low | Extend existing `recruiterDataExpiresAt` pattern; default 180-day retention; GdprPurgeScheduler already exists |
| Migration of existing contacts | Incorrect seniority inference; missing relationships | Low | Conservative: only infer obvious titles; create DISCOVERED status for unknowns; manual fix via UI |

## Test Plan

### Unit Tests

**PosterExtractor implementations** (one per ATS type):
- Happy path: HTML with poster section → correct PosterInfo
- Missing poster section → Optional.empty()
- Partial data (name but no LinkedIn URL) → empty (require at least name + URL)
- Malformed HTML → empty, no exception

**ContactPriorityScorer**:
- Known seniority → correct interviewGenerationWeight (RECRUITER=95, MANAGER=90, etc.)
- Unknown seniority → default weight (50)
- Warmth factors: shared employer → +30, same country → +25, same tech → +15
- Composite calculation: 0.6 * IGW + 0.4 * warmth, normalized 0-100
- Edge cases: null profile data → warmthScore = 0, composite uses IGW only

**RelationshipService (state machine)**:
- DISCOVERED + MESSAGE_SENT → CONTACTED
- CONTACTED + REPLIED → REPLIED
- CONTACTED + 14 days no reply → GHOSTED (via detectGhosting)
- REPLIED + REFERRAL_GIVEN → REFERRED
- REFERRED + INTERVIEW_OBTAINED → INTERVIEW_OBTAINED
- STATUS_OVERRIDE overrides any state
- Idempotent ghost detection (running twice doesn't double-transition)

**ContactDiscoveryService**:
- Top N companies selected by priorityScore DESC, filtered to ACTIVE + no existing poster contacts
- Returns early when rate limiter returns false
- Records ContactDiscoveryRun with correct counts

**Seniority inference**:
- "Senior Recruiter" → RECRUITER
- "Engineering Manager" → MANAGER
- "Staff Engineer" → STAFF
- "Software Developer" → IC
- null/blank title → null seniority

### Integration Tests

**Poster extraction end-to-end** (WireMock):
- Mock Greenhouse HTML page with poster div → verify OutreachContact created + posterContactId set on JobPosting
- Mock Lever page without poster → verify job saved without posterContactId
- Deduplication: same LinkedIn URL from two jobs → single OutreachContact, both jobs reference it

**Contact discovery with LinkedIn MCP** (WireMock):
- Mock search_people response → verify contacts saved to DB with correct company_id
- Rate limit scenario → verify run recorded with contactsFound=0
- Existing contact URL → verify dedup (not duplicated)

**Relationship event persistence**:
- Record event → verify event in DB + status updated on relationship
- Query by status with pagination

**Migration changeset**:
- Run Liquibase on test DB → verify schema, indexes, constraints
- Existing outreach_contact records get default discovered_via = MANUAL

### End-to-End Tests

**People page load**:
- API returns contacts with scores → page renders grouped by status
- Filter by company → only that company's contacts shown
- Sort by contactPriorityScore → descending order verified

**Poster extraction during crawl**:
- Trigger crawl for endpoint with poster HTML → new contact appears in /api/people
- Job card shows "1 contact" badge after crawl

**Manual discovery trigger**:
- POST /api/people/discover/{companyId} → contacts appear → discovery run recorded

### Non-Functional Tests

**Performance**:
- GET /api/people with 1000+ contacts: < 200ms (indexed queries)
- Poster extraction per job: < 50ms (HTML parsing, no network)
- Contact scoring batch (100 contacts): < 500ms

**Data integrity**:
- UNIQUE constraint on relationship.contact_id prevents duplicates
- FK constraints prevent orphaned events
- Ghost detection idempotent under concurrent execution

## profile.yaml Extensions

```yaml
people:
  discovery:
    auto-discovery-enabled: true
    daily-search-budget: 10          # max LinkedIn searches per day for automated discovery
    title-keywords: ["recruiter", "talent acquisition", "hiring manager", "engineering manager"]
    high-priority-threshold: 70      # company priorityScore above which → daily discovery
    low-priority-interval-days: 7    # companies below threshold → weekly
  scoring:
    interview-generation-weights:
      RECRUITER: 95
      MANAGER: 90
      SENIOR: 75
      STAFF: 70
      IC: 50
      DIRECTOR: 65
    warmth-factors:
      shared-employer: 30
      same-country: 25
      same-university: 15
      same-tech-stack: 15
      mutual-connections: 15
    composite-weights:
      interview-generation: 0.6
      warmth: 0.4
  relationship:
    ghosting-threshold-days: 14
    stale-contact-days: 90
  gdpr:
    contact-retention-days: 180
```

## Package Structure

```
api/src/main/java/dev/jobhunter/
├── people/                              # NEW: People module
│   ├── model/
│   │   ├── Relationship.java
│   │   ├── RelationshipEvent.java
│   │   ├── OutreachMessage.java
│   │   ├── ContactDiscoveryRun.java
│   │   └── enums/
│   │       ├── RelationshipStatus.java
│   │       ├── EventType.java
│   │       ├── Direction.java
│   │       ├── Channel.java
│   │       ├── MessageType.java
│   │       ├── Seniority.java
│   │       ├── ContactDiscoverySource.java
│   │       └── InterviewSource.java
│   ├── repository/
│   │   ├── RelationshipRepository.java
│   │   ├── RelationshipEventRepository.java
│   │   ├── OutreachMessageRepository.java
│   │   └── ContactDiscoveryRunRepository.java
│   ├── service/
│   │   ├── RelationshipService.java       # Full aggregate: status + events + ghost detection
│   │   ├── ContactDiscoveryService.java
│   │   └── ContactPriorityScorer.java     # Concrete class (no generic interface)
│   ├── poster/
│   │   ├── PosterExtractor.java           # Interface
│   │   ├── PosterExtractorRegistry.java   # Auto-registers impls, selects by AtsType
│   │   ├── PosterInfo.java                # Record
│   │   ├── PosterExtractionService.java
│   │   ├── GreenhousePosterExtractor.java
│   │   ├── LeverPosterExtractor.java
│   │   ├── AshbyPosterExtractor.java
│   │   ├── SmartRecruitersPosterExtractor.java
│   │   └── TeamtailorPosterExtractor.java
│   ├── dto/
│   │   ├── ContactDto.java
│   │   ├── ContactDetailDto.java
│   │   ├── RelationshipEventDto.java
│   │   ├── ContactDiscoveryRunDto.java
│   │   ├── PeopleStatsDto.java
│   │   └── ContactScore.java
│   └── scheduler/
│       └── ContactDiscoveryScheduler.java
├── controller/
│   └── PeopleController.java            # NEW
├── linkedin/
│   └── OutreachContact.java             # EXTENDED (new fields)
├── model/
│   ├── Application.java                 # EXTENDED (interviewSource)
│   └── enums/
│       └── InterviewSource.java         # NEW enum (in people/model/enums/ instead — see note)
└── ...
```

**Note on enum placement**: All new enums go in `people/model/enums/` for package cohesion, including `InterviewSource`. The fact that it's added as a field to `Application` (in `model/`) doesn't require the enum to live in `model/enums/` — Application imports from `people.model.enums`.

## Dashboard Changes

### New Page: `/people`

- Grouped tabs: ALL | DISCOVERED | CONTACTED | REPLIED | ENGAGED | REFERRED | GHOSTED
- Each contact card: name, title, company, seniority badge, warmth indicator (warm/cold), contactPriorityScore
- Filters: company dropdown, seniority multiselect, connection status
- Sort: by contactPriorityScore (default), by lastContactAt, by name
- Click → contact detail panel (slide-over or route)

### Contact Detail Panel

- Header: name, title, company, LinkedIn link, seniority badge
- Scores section: IGW (gauge), warmth (gauge), composite (large number)
- Relationship timeline: chronological events with type icons
- Message history: outreach messages IN/OUT
- Linked jobs: jobs where this person is poster (via posterContactId)
- Actions: record event button, trigger message (future Phase 3)

### Job Card Enhancement

- New badge: "👤 X contacts · Y connected" per company
- Shows when company has any OutreachContact records
- Click badge → filters People page by that company

### Company Page: Contacts Tab

- New tab on company detail
- Shows contacts grouped by seniority
- Seniority breakdown chart (bar)
- Top 5 contacts by score
- Relationship status distribution (donut)

### New TypeScript Types

```typescript
export type Seniority = 'RECRUITER' | 'MANAGER' | 'DIRECTOR' | 'STAFF' | 'SENIOR' | 'IC';
export type ContactDiscoverySource = 'JOB_POSTER' | 'LINKEDIN_SEARCH' | 'MANUAL';
export type RelationshipStatus = 'DISCOVERED' | 'CONTACTED' | 'REPLIED' | 'ENGAGED' | 'REFERRED' | 'INTERVIEW_OBTAINED' | 'GHOSTED' | 'COLD';
export type EventType = 'CONTACT_DISCOVERED' | 'MESSAGE_SENT' | 'REPLIED' | 'CALL_BOOKED' | 'REFERRAL_REQUESTED' | 'REFERRAL_GIVEN' | 'INTERVIEW_OBTAINED' | 'GHOSTED_AUTO' | 'STATUS_OVERRIDE';
export type InterviewSource = 'APPLICATION' | 'RECRUITER' | 'REFERRAL' | 'NETWORKING' | 'EVENT';

export interface Contact {
  id: string;
  personName: string;
  title?: string;
  linkedinUrl: string;
  companyId: string;
  companyName: string;
  seniority?: Seniority;
  discoveredVia: ContactDiscoverySource;
  connectionStatus: 'NONE' | 'PENDING' | 'CONNECTED' | 'DECLINED';
  interviewGenerationWeight: number;
  warmthScore: number;
  contactPriorityScore: number;
  relationshipStatus?: RelationshipStatus;
  lastContactAt?: string;
  createdAt: string;
}

export interface ContactDetail extends Contact {
  location?: string;
  techStack?: string[];
  events: RelationshipEvent[];
  messages: OutreachMessageDto[];
  linkedJobs: JobSummary[];
  referredBy?: Contact;
}

export interface RelationshipEvent {
  id: string;
  eventType: EventType;
  occurredAt: string;
  metadata?: Record<string, unknown>;
}

export interface PeopleStats {
  totalContacts: number;
  byStatus: Record<RelationshipStatus, number>;
  bySeniority: Record<Seniority, number>;
  avgPriorityScore: number;
  discoveredToday: number;
}
```

## Integration Points with Existing Code

| Existing Component | Integration | Change Type |
|-------------------|-------------|-------------|
| CrawlService | Call PostCrawlPipeline.run() after job persistence (see Existing Code Improvements) | Refactor: extract hook |
| LinkedInNetworkingService | Reused by ContactDiscoveryService for search | No change (consumed as-is) |
| LinkedInRateLimiter | Shared budget between manual + automated discovery | No change (already shared) |
| CompanyPriorityScorer | Used by ContactDiscoveryScheduler for priority ordering | No change (consumed) |
| GdprPurgeScheduler | Extended to purge contacts exceeding retention | Add people purge logic |
| PersonalProfileLoader | Extended to load `people` config section | Refactor: see Existing Code Improvements |
| OutreachContactRepository | Add queries for scoring, filtering, counts | Add query methods |
| DtoMapper | Add mapping methods for new DTOs | Add static methods |

---

## Existing Code Improvements

These refactors improve existing code quality and enable clean People module integration. They should be executed as Phase 0 (prep work) before Phase 1 implementation.

### 1. Extract PostCrawlPipeline from CrawlService

**Problem**: CrawlService already has 10+ constructor dependencies. Adding PosterExtractionService directly creates a God class trending toward 15+ deps.

**Solution**: Extract a `PostCrawlPipeline` that runs registered hooks after job persistence.

```java
@Component
public class PostCrawlPipeline {
    private final List<PostCrawlHook> hooks;

    public PostCrawlPipeline(List<PostCrawlHook> hooks) {
        this.hooks = hooks;
    }

    public void run(JobPosting job, FetchResult fetchResult) {
        hooks.forEach(hook -> {
            try {
                hook.afterJobPersisted(job, fetchResult);
            } catch (Exception e) {
                log.warn("PostCrawlHook {} failed for job {}: {}", 
                    hook.getClass().getSimpleName(), job.getId(), e.getMessage());
            }
        });
    }
}

public interface PostCrawlHook {
    void afterJobPersisted(JobPosting job, FetchResult fetchResult);
    default int order() { return 0; }  // lower = runs first
}
```

**PosterExtractionService** implements `PostCrawlHook`. Future hooks (e.g., skill extraction, enrichment triggers) can plug in without modifying CrawlService.

CrawlService change: replace N direct service calls with single `postCrawlPipeline.run(job, fetchResult)`.

### 2. Refactor PersonalProfileLoader Config Parsing

**Problem**: Current `parseProfile()` is 200+ lines of unchecked SnakeYAML map casting. Extending with `people` section is painful and error-prone.

**Solution**: Migrate to typed config using `@ConfigurationProperties` or at minimum, break into section-specific parsers.

```java
@Component
public class PersonalProfileLoader {
    private final PersonalProfile profile;

    public PersonalProfileLoader(@Value("${profile.path:profile.yaml}") String path) {
        this.profile = ProfileParser.parse(path);
    }

    public PersonalProfile getProfile() { return profile; }
}

// Section parsers (each handles one YAML section):
public class ProfileParser {
    public static PersonalProfile parse(String path) {
        Map<String, Object> raw = loadYaml(path);
        return new PersonalProfile(
            SkillsParser.parse(raw),
            PreferencesParser.parse(raw),
            FiltersParser.parse(raw),
            ScoringParser.parse(raw),
            PeopleParser.parse(raw)     // NEW: handles people.discovery, people.scoring, etc.
        );
    }
}
```

Each section parser is independently testable and extendable.

### 3. Standardize Scoring Interfaces (Lightweight)

**Problem**: Existing scorers have wildly different signatures:
- `CompanyPriorityScorer.score(Company) → double`
- `MatchScorer.score(JobPosting, PersonalProfile) → MatchResult`
- `OpportunityScorer.score(JobPosting, MatchScore) → OpportunityResult`

**Solution**: Don't force a generic interface (YAGNI). Instead, standardize the pattern:
- Each scorer is a `@Component` with a `score()` method
- Each returns a record containing composite score + breakdown map
- Each reads config from `PersonalProfileLoader` at construction

New scorers (`ContactPriorityScorer`, `ActionScorer`) follow this convention. Existing scorers are left as-is unless touched for other reasons. A future unified `Scorer<T>` refactor can happen when there are 6+ scorers all needing polymorphic dispatch (today there's no code that treats scorers generically).

### 4. FetchResult Enrichment

**Problem**: `PosterExtractionService` needs raw HTML/JSON from crawl. Currently `FetchResult` (or equivalent) may not preserve both formats.

**Solution**: Ensure FetchResult carries:
- `rawHtml: String` (the page content)
- `rawJson: Map<String, Object>` (if ATS returned JSON API, e.g., Greenhouse/Ashby)
- `atsType: AtsType` (so PosterExtractorRegistry can select without re-detection)

If FetchResult doesn't have these, extend it. This is a non-breaking additive change.

### 5. OutreachContact Package Boundary

**Problem**: `OutreachContact` lives in `linkedin/` package but gains People-domain fields (`seniority`, `warmthScore`, `contactPriorityScore`).

**Decision**: Keep entity in `linkedin/` (avoid JPA migration complexity of table rename/entity move). Accept the bidirectional coupling. Document that `linkedin/` is the "person storage" layer consumed by both MCP tools and People module.

Long-term option: rename package from `linkedin/` to `contacts/` in a future cleanup pass (JPA doesn't care about package names, only `@Entity` + `@Table`).

---

## Cross-Cutting Abstractions

Design principle: **extract interfaces when the second real implementation emerges, not before** (YAGNI). Only `AiTask<I,O>` earns a generic interface in this module (6 implementations). All other shared patterns use concrete classes following existing codebase conventions.

### AiTask\<I, O\> Interface

Outreach generation (Phase 3) and funnel analysis (Phase 4) both: assemble context → call AiProvider → parse response. 6 implementations guaranteed. Shared abstraction:

```java
public interface AiTask<I, O> {
    String systemPrompt(I input);
    String userPrompt(I input);
    O parseResponse(String raw, I input);

    default O execute(AiProvider provider, I input) {
        String response = provider.generate(systemPrompt(input), userPrompt(input));
        return parseResponse(response, input);
    }
}
```

Implementations: `InfoChatTask`, `TechDiscussionTask`, `ReferralAskTask`, `FollowUpTask`, `RecruiterPitchTask`, `FunnelAnalysisTask`. Each has its own prompt assembly and parsing logic but shares the execution pattern.

### Scorers: Concrete Classes (No Generic Interface)

Existing codebase has 3 scorers (`CompanyPriorityScorer`, `MatchScorer`, `OpportunityScorer`) — all standalone concrete classes with different return types and signatures. **We follow this pattern.** New scorers (`ContactPriorityScorer`, `ActionScorer`) are concrete classes. No `Scorer<T>` interface.

Future refactor opportunity: if/when all 5+ scorers converge on a shared contract, extract interface then. Not now — premature generalization with adapter boilerplate for zero polymorphic benefit.

### OpportunityQueue: Concrete Class

Discovery priority is SQL `ORDER BY priorityScore DESC` — no abstraction needed. OpportunityQueue is a standalone service class with `getToday(int limit)` method. No `PrioritizedQueue<T>` interface (one implementation doesn't justify an interface).

### OutreachContextAssembler: Concrete Utility

Assembles multi-source context for AI message generation. Concrete class, no interface (FunnelAnalysisTask assembles its own context inline — different shape entirely).

```java
public class OutreachContextAssembler {
    public AssembledContext assemble(OutreachContext target) { ... }
}

public record AssembledContext(
    Map<String, String> sections,    // "contact_profile" → text, "job_description" → text
    int estimatedTokens
) {
    public String toPromptString() {
        return sections.entrySet().stream()
            .map(e -> "## " + e.getKey() + "\n" + e.getValue())
            .collect(Collectors.joining("\n\n"));
    }
}
```

### RelationshipEvent: Standalone Entity

No `DomainEvent` base interface. `PipelineEvent` doesn't exist as an entity — funnel is derived from existing `Application` + `JobOutcome`. RelationshipEvent is the sole event-sourced entity. If a second event-sourced aggregate emerges later, extract shared interface then.

---

## Phase 3: AI Outreach

### Components (Phase 3)

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| OutreachMessageGenerator | Orchestrate context assembly + AI generation per variant | AiProvider, ContextAssembler, PersonalProfileLoader |
| OutreachMessageTask (per variant) | Variant-specific prompt + parsing (implements AiTask) | None (pure logic) |
| OutreachContextAssembler | Build context from contact profile, job, resume, history | RelationshipService, PersonalProfileLoader |
| OutreachController | REST endpoint for message generation | OutreachMessageGenerator, OutreachMessageRepository |

### Interfaces (Phase 3)

#### OutreachMessageGenerator

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| generate | `(UUID contactId, MessageVariant variant, UUID jobId?)` | `GeneratedMessage` | Assemble context, select prompt template, call AI, return draft | ContactNotFound (404), AiProviderUnavailable (503) |
| getAvailableVariants | `(UUID contactId)` | `List<MessageVariant>` | Return applicable variants based on relationship status | None |

```java
public record GeneratedMessage(
    String content,
    MessageVariant variant,
    UUID contactId,
    UUID jobId,              // nullable
    String modelUsed,
    int tokensUsed
) {}

public enum MessageVariant {
    INFO_CHAT,           // casual intro, learn about company/role
    TECH_DISCUSSION,     // shared tech interest as opener
    REFERRAL_ASK,        // request referral (only if ENGAGED+)
    FOLLOW_UP,           // re-engage after silence
    RECRUITER_PITCH      // pitch to recruiter (different tone)
}
```

#### OutreachMessageTask (implements AiTask)

```java
public class InfoChatTask implements AiTask<OutreachContext, GeneratedMessage> { ... }
public class TechDiscussionTask implements AiTask<OutreachContext, GeneratedMessage> { ... }
public class ReferralAskTask implements AiTask<OutreachContext, GeneratedMessage> { ... }
public class FollowUpTask implements AiTask<OutreachContext, GeneratedMessage> { ... }
public class RecruiterPitchTask implements AiTask<OutreachContext, GeneratedMessage> { ... }
```

Each task has fundamentally different system prompts:
- **INFO_CHAT**: conversational, asks specific question about their work, no ask
- **TECH_DISCUSSION**: reference shared stack/project, offer insight or ask opinion
- **REFERRAL_ASK**: acknowledge relationship, explain fit, specific ask for referral
- **FOLLOW_UP**: reference previous interaction, add new value/update, gentle re-engage
- **RECRUITER_PITCH**: professional, highlight match to their open role, call-to-action

#### OutreachContextAssembler (implements ContextAssembler\<OutreachContext\>)

```java
public record OutreachContext(
    OutreachContact contact,
    Relationship relationship,
    List<RelationshipEvent> events,
    List<OutreachMessage> messageHistory,
    JobPosting targetJob,             // nullable
    PersonalProfile userProfile,
    MessageVariant variant
) {}
```

Assembles sections: `contact_profile`, `relationship_history`, `target_job`, `user_background`, `previous_messages`, `constraints` (max length, no cringe, etc.)

#### OutreachController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| POST /api/contacts/{id}/generate-message | `{variant: MessageVariant, jobId?: UUID}` | `GeneratedMessageDto` | Generate draft, do NOT auto-send | 404, 503 |
| POST /api/contacts/{id}/messages | `{content, channel, messageType}` | `OutreachMessageDto` | Save sent message, record MESSAGE_SENT event | 404, 400 |
| GET /api/contacts/{id}/messages | UUID | `List<OutreachMessageDto>` | Message history | 404 |

### Data Flow (Phase 3)

#### Message Generation Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Dashboard | User clicks "Generate" on contact card, selects variant | OutreachController |
| 2 | OutreachController | Validates contact exists, checks variant applicability | OutreachMessageGenerator |
| 3 | OutreachMessageGenerator | Loads contact, relationship, job, profile | OutreachContextAssembler |
| 4 | OutreachContextAssembler | Assembles context sections from multiple sources | Back to generator |
| 5 | OutreachMessageGenerator | Selects AiTask implementation for variant | AiTask.execute() |
| 6 | AiTask | Builds system+user prompt, calls AiProvider.generate() | AiProvider |
| 7 | AiProvider | Returns raw text | AiTask.parseResponse() |
| 8 | OutreachMessageGenerator | Wraps in GeneratedMessage, returns to controller | Controller → 200 |

#### Message Send Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Dashboard | User edits draft, clicks "Send" | OutreachController |
| 2 | OutreachController | Saves OutreachMessage (direction=OUT) | OutreachMessageRepository |
| 3 | OutreachController | Records MESSAGE_SENT event on relationship | RelationshipService |
| 4 | RelationshipService | Updates relationship status (DISCOVERED→CONTACTED) | Relationship saved |

**Error Flows**:
- AI provider unavailable → 503, UI shows "AI unavailable, write manually"
- Contact has no LinkedIn URL → message generated but "send via LinkedIn" disabled
- Variant not applicable (e.g., REFERRAL_ASK when status=DISCOVERED) → 400 with reason

### Data Model (Phase 3)

Extends existing `outreach_message` table (from Phase 2):

| Entity | New Fields | Purpose |
|--------|-----------|---------|
| OutreachMessage | `template_used: MessageVariant (nullable), ai_generated: boolean, tokens_used: int` | Track which variant produced the message, AI vs manual |

**Changeset 7**: Extend outreach_message
```sql
ALTER TABLE outreach_message ADD COLUMN template_used VARCHAR(30);
ALTER TABLE outreach_message ADD COLUMN ai_generated BOOLEAN DEFAULT FALSE;
ALTER TABLE outreach_message ADD COLUMN tokens_used INTEGER DEFAULT 0;
```

### Dashboard Changes (Phase 3)

**Contact card "Generate" button**:
- Dropdown: variant selector (Info Chat, Tech Discussion, Referral Ask, Follow-Up, Recruiter Pitch)
- Variant availability based on relationship status:
  - DISCOVERED: Info Chat, Tech Discussion, Recruiter Pitch
  - CONTACTED/REPLIED: Follow-Up, Tech Discussion, Referral Ask
  - ENGAGED: Referral Ask, Follow-Up
  - All: Recruiter Pitch (for recruiter-seniority contacts)

**Message preview panel**:
- Generated message displayed in editable textarea
- Token count shown
- "Regenerate" button (new AI call)
- "Copy to clipboard" / "Mark as sent" buttons
- After "Mark as sent": message saved, event recorded, relationship status updated

**Message history tab on contact detail**:
- Shows all IN/OUT messages with variant badge
- AI-generated messages tagged with ✨ indicator

### profile.yaml Extensions (Phase 3)

```yaml
people:
  outreach:
    templates:
      info-chat:
        max-length: 300
        tone: "curious, specific, no generic compliments"
        constraints: "ask one specific question about their work, reference something concrete"
      tech-discussion:
        max-length: 350
        tone: "peer-to-peer, technically specific"
        constraints: "reference shared tech stack, offer insight or ask opinion on specific problem"
      referral-ask:
        max-length: 400
        tone: "appreciative, direct, specific"
        constraints: "acknowledge relationship, explain exact role fit, make specific ask"
      follow-up:
        max-length: 250
        tone: "brief, adds new value"
        constraints: "reference previous interaction, share update or new info, gentle re-engage"
      recruiter-pitch:
        max-length: 350
        tone: "professional, highlight match"
        constraints: "mention specific open role, 2-3 concrete skill matches, clear CTA"
    global-constraints:
      - "never use 'hope this finds you well' or similar cliches"
      - "max 1 emoji, only if natural"
      - "first name only, never 'Dear X'"
      - "no more than 2 paragraphs for LinkedIn messages"
```

### Test Plan (Phase 3)

**Unit Tests**:

- `OutreachMessageGenerator`: mock AiProvider, verify correct task selected per variant
- `InfoChatTask`: verify system prompt includes curiosity constraints, user prompt includes contact profile
- `TechDiscussionTask`: verify tech stack overlap extracted and included in prompt
- `ReferralAskTask`: verify relationship status gate (only ENGAGED+ allowed)
- `FollowUpTask`: verify previous messages included in context
- `OutreachContextAssembler`: verify all sections assembled from input data, handles nulls (no job, no history)
- Variant availability: DISCOVERED → no REFERRAL_ASK; RECRUITER seniority → always has RECRUITER_PITCH

**Integration Tests (WireMock)**:

- POST /api/contacts/{id}/generate-message with mock AI response → verify GeneratedMessageDto shape
- POST /api/contacts/{id}/messages → verify OutreachMessage persisted + RelationshipEvent created
- Generate with missing contact → 404
- Generate with unavailable AI → 503

---

## Phase 4: Pipeline Analytics + Opportunity Queue

### Components (Phase 4)

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| FunnelAggregator | Compute conversion rates across pipeline stages | ApplicationRepository, JobOutcomeRepository |
| FunnelAnalysisTask | AI analysis of funnel shape (implements AiTask) | AiProvider, FunnelAggregator |
| ActionScorer | Compute actionScore = impactScore × urgencyScore per action candidate (implements Scorer) | PersonalProfileLoader |
| OpportunityQueue | Assemble and rank daily actions from multiple sources | ActionScorer, RelationshipService, ApplicationRepository |
| PipelineController | REST API for funnel + actions | FunnelAggregator, OpportunityQueue, FunnelAnalysisTask |
| EffectivenessTracker | Correlate outreach variants with outcomes over time | OutreachMessageRepository, ApplicationRepository |

### Interfaces (Phase 4)

#### FunnelAggregator

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| aggregate | `(LocalDate from, LocalDate to)` | `FunnelData` | Count applications per stage, compute conversions | None |
| aggregateBySource | `(LocalDate from, LocalDate to)` | `Map<InterviewSource, FunnelData>` | Breakdown by how interview was obtained | None |

```java
public record FunnelData(
    int applications,
    int recruiterScreen,
    int technical,
    int finalRound,
    int offers,
    Map<String, Double> conversionRates,     // "application_to_screen" → 0.25
    Map<String, Double> avgDaysBetweenStages  // "screen_to_technical" → 5.2
) {}
```

#### FunnelAnalysisTask (implements AiTask\<FunnelData, FunnelAnalysis\>)

```java
public record FunnelAnalysis(
    String primaryBottleneck,          // "top-of-funnel" or "interviewing" or "closing"
    String explanation,                // evidence-based reasoning
    List<String> suggestions,          // actionable, specific
    Map<String, String> stageInsights  // per-stage observations
) {}
```

#### ActionScorer (implements Scorer\<ActionCandidate\>)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| score | `(ActionCandidate candidate)` | `ScoreResult` | Compute impact × urgency | None |
| scoreBatch | `(List<ActionCandidate> candidates)` | `List<ScoreResult>` | Batch scoring | None |

```java
public record ActionCandidate(
    UUID entityId,                // contact or application ID
    ActionType type,             // FOLLOW_UP, CONNECT, APPLY, PREPARE, SEND_MESSAGE
    UUID contactId,              // nullable
    UUID jobId,                  // nullable
    Instant lastActivity,        // when last interaction occurred
    Instant deadline,            // when this action expires/becomes stale
    double baseImpact            // from contact priority or job match score
) {}

public record ScoredAction(
    UUID entityId,
    ActionType type,
    double impactScore,          // 0-100: interview-generation potential
    double urgencyScore,         // 0-1: time decay multiplier
    double actionScore,          // impactScore × urgencyScore
    String reason,               // "Recruiter replied 6 days ago — follow-up window closing"
    String expiresIn,            // "2 days", "today", "overdue"
    UUID contactId,
    UUID jobId,
    String contactName,
    String companyName,
    String jobTitle
) {}
```

**Urgency calculation**:
```
urgencyScore = clamp(0, 1, 1.0 - (daysSinceDeadline / totalWindow))

Examples:
- Reply 6 days ago, follow-up window = 7 days → urgency = 1.0 - (6/7) ≈ 0.14... wait
  Actually: urgencyScore = daysUntilExpiry / totalWindow inverted
  Better: urgencyScore = 1.0 - (daysRemaining / totalWindow) if daysRemaining > 0
           = 1.5 if overdue (bonus for overdue items)

- Last reply 6 days ago, 7-day window → 1 day remaining → urgency = 1.0 - (1/7) = 0.86
- New contact today, 14-day window → 14 days remaining → urgency = 1.0 - (14/14) = 0.0
- Reply 8 days ago, 7-day window → overdue → urgency = 1.2 (capped boost)
```

#### OpportunityQueue (implements PrioritizedQueue\<ScoredAction\>)

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| getToday | `(int limit)` | `List<ScoredAction>` | Gather candidates from all sources, score, sort by actionScore DESC | None |
| getTopN | `(int n)` | `List<ScoredAction>` | Alias for getToday | None |

**Action sources** (candidates gathered from):
1. **Follow-up overdue**: Contacts with REPLIED status + no MESSAGE_SENT in last N days
2. **Connection requests pending review**: New contacts with high priority, not yet connected
3. **Applications needing action**: Applications in APPLIED for >7 days with no outcome
4. **Interview prep**: Applications with upcoming interviews (from JobOutcome stage transitions)
5. **New high-match jobs**: Jobs scored today above APPLY threshold, not yet applied

#### EffectivenessTracker

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| getVariantEffectiveness | `(LocalDate from, LocalDate to)` | `Map<MessageVariant, EffectivenessMetrics>` | Correlate variants with outcomes | None |
| getChannelEffectiveness | `(LocalDate from, LocalDate to)` | `Map<InterviewSource, EffectivenessMetrics>` | Which channels produce interviews | None |

```java
public record EffectivenessMetrics(
    int totalSent,
    int replies,
    double replyRate,
    int interviewsGenerated,
    double interviewConversionRate,
    int sampleSize                   // warn if < 30 (not statistically valid)
) {}
```

#### PipelineController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| GET /api/pipeline/funnel | `?from,to` | `FunnelDto` | Aggregated funnel with conversion rates | None |
| GET /api/pipeline/funnel/by-source | `?from,to` | `Map<InterviewSource, FunnelDto>` | Per-source breakdown | None |
| POST /api/pipeline/analyze | - | `FunnelAnalysisDto` | AI analysis of current funnel shape | 503 if AI unavailable |
| GET /api/actions/today | `?limit` | `List<ScoredActionDto>` | Today's prioritized actions | None |
| GET /api/pipeline/effectiveness | `?from,to` | `EffectivenessDto` | Variant + channel effectiveness | None |

### Data Flow (Phase 4)

#### Daily Action Queue Generation

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineController | GET /api/actions/today called (or scheduler pre-computes) | OpportunityQueue |
| 2 | OpportunityQueue | Queries overdue follow-ups from RelationshipService | ActionCandidate list |
| 3 | OpportunityQueue | Queries pending connections from OutreachContactRepository | ActionCandidate list |
| 4 | OpportunityQueue | Queries stale applications from ApplicationRepository | ActionCandidate list |
| 5 | OpportunityQueue | Queries high-match unacted jobs from JobPostingRepository | ActionCandidate list |
| 6 | OpportunityQueue | Merges all candidates into single list | ActionScorer |
| 7 | ActionScorer | Computes impactScore + urgencyScore → actionScore per candidate | List<ScoredAction> |
| 8 | OpportunityQueue | Sorts by actionScore DESC, returns top N | Controller → 200 |

#### Funnel Analysis Flow

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | PipelineController | POST /api/pipeline/analyze | FunnelAggregator |
| 2 | FunnelAggregator | Queries Application + JobOutcome tables, computes stage counts | FunnelData |
| 3 | PipelineController | Passes FunnelData to FunnelAnalysisTask | FunnelAnalysisTask |
| 4 | FunnelAnalysisTask | Assembles prompt with funnel numbers + context | AiProvider |
| 5 | AiProvider | Returns analysis text | FunnelAnalysisTask.parseResponse() |
| 6 | FunnelAnalysisTask | Parses into FunnelAnalysis record | Controller → 200 |

**Error Flows**:
- AI unavailable for funnel analysis → 503, funnel data still returned without analysis
- No applications in date range → empty FunnelData (zeros), no AI call triggered
- Action queue with no candidates → empty list (user has nothing to do today)

### Data Model (Phase 4)

#### Mapping to Existing Entities

The funnel maps directly to existing `ApplicationStatus` + `OutcomeStage`:

| Funnel Stage | Source |
|-------------|--------|
| Applications | `Application` count (status ≠ INTERESTED) |
| Recruiter Screen | `JobOutcome` with stage = PHONE_SCREEN |
| Technical | `JobOutcome` with stage = INTERVIEW_1 or INTERVIEW_2 |
| Final Round | Future: add `FINAL_ROUND` to OutcomeStage |
| Offer | `ApplicationStatus.OFFERED` or `OutcomeStage.OFFER` |

#### New/Extended Entities

| Entity | Fields | Relationships | Constraints |
|--------|--------|---------------|-------------|
| Application (extended) | `interview_source: InterviewSource` | (already designed in Phase 2) | nullable |

**Changeset 8**: Add FINAL_ROUND to outcome stages + interview_source
```sql
-- OutcomeStage already handles as VARCHAR, just use new value 'FINAL_ROUND'
-- No ALTER needed for enum since using @Enumerated(EnumType.STRING)

-- If not already added in Phase 2 changeset 6:
ALTER TABLE application ADD COLUMN IF NOT EXISTS interview_source VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_application_interview_source ON application(interview_source);
CREATE INDEX IF NOT EXISTS idx_application_status_date ON application(status, applied_date);
CREATE INDEX IF NOT EXISTS idx_job_outcome_stage ON job_outcome(stage, occurred_at);
```

#### Updated OutcomeStage Enum

```java
public enum OutcomeStage {
    APPLIED,
    PHONE_SCREEN,
    INTERVIEW_1,
    INTERVIEW_2,
    FINAL_ROUND,    // NEW
    OFFER,
    REJECTED,
    WITHDRAWN
}
```

### Dashboard Changes (Phase 4)

**New page: `/today` (replaces daily digest as primary landing)**:

- Action queue: cards sorted by actionScore
- Each action card shows:
  - Action type icon (🔄 follow-up, 🤝 connect, 📝 apply, 📚 prepare, ✉️ message)
  - Person name + company (or job title + company)
  - Impact score (gauge/badge)
  - Urgency indicator (🟢 low, 🟡 medium, 🔴 high, ⚫ overdue)
  - Reason text: "Recruiter replied 6 days ago — follow-up window closing"
  - Expires in: "1 day" / "today" / "overdue"
  - Quick action button (context-dependent: "Generate Message", "Apply", "View Contact")
- Empty state: "Nothing urgent today 🎉 — consider reaching out to new contacts"

**New page: `/pipeline`**:

- Funnel visualization (horizontal bar chart showing stage → stage with dropout)
- Conversion rates between stages
- Source breakdown toggle (ALL / APPLICATION / REFERRAL / NETWORKING / etc.)
- "Analyze" button → AI analysis panel (bottleneck identification + suggestions)
- Time range selector (last 30d / 90d / all time)
- Effectiveness section: variant reply rates, channel interview rates (with sample size warnings)

**New TypeScript Types**:

```typescript
export type ActionType = 'FOLLOW_UP' | 'CONNECT' | 'APPLY' | 'PREPARE' | 'SEND_MESSAGE';

export interface ScoredAction {
  entityId: string;
  type: ActionType;
  impactScore: number;
  urgencyScore: number;
  actionScore: number;
  reason: string;
  expiresIn: string;
  contactId?: string;
  jobId?: string;
  contactName?: string;
  companyName: string;
  jobTitle?: string;
}

export interface FunnelData {
  applications: number;
  recruiterScreen: number;
  technical: number;
  finalRound: number;
  offers: number;
  conversionRates: Record<string, number>;
  avgDaysBetweenStages: Record<string, number>;
}

export interface FunnelAnalysis {
  primaryBottleneck: string;
  explanation: string;
  suggestions: string[];
  stageInsights: Record<string, string>;
}

export interface EffectivenessMetrics {
  totalSent: number;
  replies: number;
  replyRate: number;
  interviewsGenerated: number;
  interviewConversionRate: number;
  sampleSize: number;
}
```

### profile.yaml Extensions (Phase 4)

```yaml
people:
  pipeline:
    action-windows:
      follow-up-days: 7           # follow up within N days of last reply
      connection-review-days: 3   # review new contacts within N days
      application-stale-days: 10  # flag applications with no progress after N days
    urgency:
      overdue-boost: 1.2          # urgency multiplier when past deadline
      max-urgency: 1.5            # cap
    effectiveness:
      min-sample-size: 30         # warn below this threshold
    funnel:
      stages: ["APPLIED", "PHONE_SCREEN", "TECHNICAL", "FINAL_ROUND", "OFFER"]
```

### Test Plan (Phase 4)

**Unit Tests**:

- `FunnelAggregator`: mock repos returning stage counts → verify conversion rate math
- `FunnelAggregator`: empty data → all zeros, no division by zero
- `FunnelAggregator`: by-source breakdown sums to total
- `ActionScorer`: follow-up 6 days ago (7-day window) → urgencyScore ≈ 0.86
- `ActionScorer`: new contact today (14-day window) → urgencyScore ≈ 0.0
- `ActionScorer`: overdue item → urgencyScore = 1.2 (capped)
- `ActionScorer`: actionScore = impactScore × urgencyScore verified
- `OpportunityQueue`: merges from 5 sources, deduplicates (same contact appearing as follow-up AND connection)
- `OpportunityQueue`: sorts by actionScore DESC
- `FunnelAnalysisTask`: verify prompt includes actual numbers, not generic template
- `EffectivenessTracker`: handles zero-sent (no division by zero), flags sampleSize < 30

**Integration Tests**:

- GET /api/actions/today with seeded data → verify sorted actions with correct scores
- GET /api/pipeline/funnel with applications across stages → correct counts
- GET /api/pipeline/funnel/by-source → correct source attribution
- POST /api/pipeline/analyze with mock AI → parseable FunnelAnalysis returned
- Empty pipeline → empty results, no errors

---

## Phase 5: Company Intelligence (Raw Facts)

### Components (Phase 5)

| Component | Responsibility | Dependencies |
|-----------|---------------|--------------|
| CompanyEnrichmentService | Populate company fields from LinkedIn MCP data | LinkedInProfileService, CompanyRepository |
| CompanyEnrichmentScheduler | Periodic refresh for ACTIVE companies | CompanyEnrichmentService, Quartz |
| HiringVelocityCalculator | Count jobs posted in last 30 days per company | JobPostingRepository |
| VisaSignalService | Manage raw visa signals (no AI inference without confirmation) | CompanyRepository |

### Interfaces (Phase 5)

#### CompanyEnrichmentService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| enrich | `(UUID companyId)` | `CompanyEnrichmentResult` | Fetch LinkedIn company data, update fields | CompanyNotFound (404), LinkedIn rate limit (skip) |
| enrichBatch | `(List<UUID> companyIds)` | `List<CompanyEnrichmentResult>` | Batch enrichment respecting rate limits | Partial failures OK |
| getEnrichmentStatus | `(UUID companyId)` | `EnrichmentStatus` | When last enriched, which fields populated | None |

```java
public record CompanyEnrichmentResult(
    UUID companyId,
    boolean success,
    Set<String> fieldsUpdated,    // "industry", "employeeCount", etc.
    String failureReason          // nullable
) {}

public record EnrichmentStatus(
    UUID companyId,
    LocalDateTime lastEnrichedAt,
    Set<String> populatedFields,
    Set<String> emptyFields
) {}
```

#### HiringVelocityCalculator

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| calculate | `(UUID companyId)` | `int` | Count distinct jobs posted in last 30 days | 0 if no jobs |
| calculateAll | `()` | `Map<UUID, Integer>` | Batch for all active companies | None |

#### VisaSignalService

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| getSignals | `(UUID companyId)` | `VisaSignals` | Return raw boolean signals | None |
| updateSignal | `(UUID companyId, String signal, boolean value)` | `VisaSignals` | User confirms/updates a signal | 400 if invalid signal name |
| suggestFromData | `(UUID companyId)` | `VisaSuggestion` | AI suggests signals from available data → NOT auto-applied | None |

```java
public record VisaSignals(
    UUID companyId,
    Boolean hasSponsoredBefore,       // nullable = unknown
    Boolean englishSpeaking,
    Boolean internationalWorkforce,
    VisaFriendliness derived          // computed from signals, UNKNOWN if insufficient data
) {}

public enum VisaFriendliness {
    UNKNOWN,   // insufficient signals
    LOW,       // all signals false
    MEDIUM,    // mixed signals
    HIGH       // all signals true
}

public record VisaSuggestion(
    Map<String, Boolean> suggestedValues,  // "hasSponsoredBefore" → true
    Map<String, String> evidence,          // "hasSponsoredBefore" → "Job listing mentions visa sponsorship"
    boolean requiresConfirmation           // always true
) {}
```

**VisaFriendliness derivation (deterministic, no AI)**:
- All 3 signals `true` → HIGH
- 2 of 3 `true` → MEDIUM
- 1 of 3 `true` → MEDIUM
- All `false` → LOW
- Any `null` → UNKNOWN (insufficient data)

#### CompanyIntelligenceController

| Method | Input | Output | Behavior | Errors |
|--------|-------|--------|----------|--------|
| GET /api/companies/{id}/intelligence | UUID | `CompanyIntelligenceDto` | All raw facts + enrichment status | 404 |
| POST /api/companies/{id}/enrich | UUID | `CompanyEnrichmentResult` | Trigger manual enrichment | 404, 429 |
| GET /api/companies/{id}/visa-signals | UUID | `VisaSignalsDto` | Raw visa signals | 404 |
| PUT /api/companies/{id}/visa-signals | `{signal, value}` | `VisaSignalsDto` | User confirms/updates signal | 400, 404 |
| POST /api/companies/{id}/visa-suggest | UUID | `VisaSuggestionDto` | AI suggests signals (not auto-applied) | 404, 503 |

### Data Flow (Phase 5)

#### Company Enrichment Flow (scheduled)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | CompanyEnrichmentScheduler | Fires weekly, selects ACTIVE companies with stale/empty enrichment | CompanyEnrichmentService |
| 2 | CompanyEnrichmentService | Checks LinkedIn rate limiter for budget | LinkedInProfileService |
| 3 | LinkedInProfileService | Calls MCP to fetch company profile data | Returns raw data |
| 4 | CompanyEnrichmentService | Maps LinkedIn data → Company fields (industry, employeeCount, specialties) | CompanyRepository |
| 5 | HiringVelocityCalculator | Counts jobs posted in last 30 days | Updates hiringVelocity field |
| 6 | CompanyEnrichmentService | Sets linkedinEnrichedAt timestamp | Company saved |

#### Visa Signal Suggestion Flow (user-initiated)

| Step | Component | Action | Next |
|------|-----------|--------|------|
| 1 | Dashboard | User clicks "Suggest visa signals" on company | CompanyIntelligenceController |
| 2 | Controller | Calls VisaSignalService.suggestFromData() | VisaSignalService |
| 3 | VisaSignalService | Gathers job descriptions, company profile | AiProvider |
| 4 | AiProvider | Analyzes for visa sponsorship signals | Returns suggestions |
| 5 | VisaSignalService | Wraps as VisaSuggestion (requiresConfirmation=true) | Controller → 200 |
| 6 | Dashboard | Shows suggestions with "Confirm" / "Reject" per signal | User action |
| 7 | Dashboard | User confirms → PUT /api/companies/{id}/visa-signals | VisaSignalService.updateSignal() |

**Error Flows**:
- LinkedIn rate limit during enrichment → skip company, try next cycle
- Company has no linkedinUrl → skip, mark as "not enrichable"
- AI suggestion fails → 503, user can still manually set signals
- Partial enrichment (some fields populated, some failed) → save what we got, report partial success

### Data Model (Phase 5)

#### Extended Company Entity

| Entity | New Fields | Purpose |
|--------|-----------|---------|
| Company | `hiringVelocity: Integer, employeeGrowth: String, fundingStage: String, hasSponsoredBefore: Boolean, englishSpeaking: Boolean, internationalWorkforce: Boolean, visaFriendliness: VisaFriendliness` | Raw intelligence facts |

**Changeset 9**: Extend company with intelligence fields
```sql
ALTER TABLE company ADD COLUMN hiring_velocity INTEGER;
ALTER TABLE company ADD COLUMN employee_growth VARCHAR(50);
ALTER TABLE company ADD COLUMN funding_stage VARCHAR(50);
ALTER TABLE company ADD COLUMN has_sponsored_before BOOLEAN;
ALTER TABLE company ADD COLUMN english_speaking BOOLEAN;
ALTER TABLE company ADD COLUMN international_workforce BOOLEAN;
ALTER TABLE company ADD COLUMN visa_friendliness VARCHAR(10) DEFAULT 'UNKNOWN';
```

#### New Enum

| Enum | Values |
|------|--------|
| VisaFriendliness | UNKNOWN, LOW, MEDIUM, HIGH |

### Dashboard Changes (Phase 5)

**Company detail page — "Intelligence" section**:

- Raw facts grid:
  - Industry: {value or "—"}
  - Employees: {count or "—"}
  - Specialties: {chips or "—"}
  - Hiring velocity: "{N} jobs in last 30 days"
  - Employee growth: {value or "—"}
  - Funding stage: {value or "—"}
- Visa section:
  - Signal indicators: ✅/❌/❓ for each signal (hasSponsoredBefore, englishSpeaking, internationalWorkforce)
  - Derived badge: UNKNOWN (gray) / LOW (red) / MEDIUM (yellow) / HIGH (green)
  - "Suggest" button → AI suggestion panel with confirm/reject per signal
  - Edit button → manual toggle per signal
- Enrichment status: "Last enriched: 3 days ago" / "Never enriched" with "Refresh" button
- NO composite score. Raw facts only. User judges.

**Company list page enhancement**:

- New columns (optional, togglable): hiringVelocity, visaFriendliness badge
- Filter by visa friendliness

**New TypeScript Types**:

```typescript
export type VisaFriendliness = 'UNKNOWN' | 'LOW' | 'MEDIUM' | 'HIGH';

export interface CompanyIntelligence {
  companyId: string;
  industry?: string;
  employeeCount?: number;
  specialties?: string;
  hiringVelocity?: number;
  employeeGrowth?: string;
  fundingStage?: string;
  visaSignals: VisaSignals;
  lastEnrichedAt?: string;
}

export interface VisaSignals {
  hasSponsoredBefore?: boolean;
  englishSpeaking?: boolean;
  internationalWorkforce?: boolean;
  derived: VisaFriendliness;
}

export interface VisaSuggestion {
  suggestedValues: Record<string, boolean>;
  evidence: Record<string, string>;
  requiresConfirmation: true;
}
```

### profile.yaml Extensions (Phase 5)

```yaml
people:
  company-intelligence:
    enrichment:
      enabled: true
      schedule-cron: "0 0 6 ? * SUN"   # weekly Sunday 6am
      max-per-run: 20                    # max companies enriched per cycle
      stale-after-days: 30               # re-enrich after N days
    visa:
      auto-suggest: false                # never auto-apply AI suggestions
```

### Test Plan (Phase 5)

**Unit Tests**:

- `CompanyEnrichmentService`: mock LinkedIn MCP response → verify fields mapped correctly
- `CompanyEnrichmentService`: partial LinkedIn response (missing fields) → only available fields updated
- `CompanyEnrichmentService`: rate limited → returns failure result with reason
- `HiringVelocityCalculator`: 5 jobs in last 30 days → returns 5
- `HiringVelocityCalculator`: jobs older than 30 days excluded
- `VisaSignalService`: all true → HIGH, all false → LOW, mixed → MEDIUM, any null → UNKNOWN
- `VisaSignalService`: updateSignal with invalid name → error
- `VisaSignalService`: suggestFromData returns requiresConfirmation=true always

**Integration Tests (WireMock)**:

- POST /api/companies/{id}/enrich with mock LinkedIn → verify company fields updated in DB
- GET /api/companies/{id}/intelligence → correct DTO with all populated fields
- PUT /api/companies/{id}/visa-signals → persisted, derived visa_friendliness recomputed
- CompanyEnrichmentScheduler fires → enriches top N companies, skips rate-limited ones
- Company without linkedinUrl → skipped gracefully

---

## Decisions (Phases 3-5)

| Decision | Choice | Reason | Alternatives | Tradeoffs |
|----------|--------|--------|--------------|-----------|
| AiTask interface for AI operations | Typed abstraction over prompt assembly + parsing | Reusable pattern; each task testable in isolation; system/user prompts decoupled | Inline AiProvider.generate() calls per service | More classes; worth it for testability and variant explosion |
| Scorer\<T\> generic interface | Shared scoring contract across modules | ContactPriorityScorer, ActionScorer, CompanyPriorityScorer all follow same shape | Each scorer as standalone class | Retrofit existing CompanyPriorityScorer may be non-trivial; adapter pattern handles it |
| Message variants as separate AiTask classes | One class per variant (not config-driven prompt switching) | Fundamentally different prompts/constraints/context needs per variant; config can't capture structural differences | Single class with if/else on variant type | More files but each is focused + independently testable |
| actionScore = impact × urgency (multiplicative) | Multiplicative ensures both dimensions matter | High impact but no urgency = deprioritized; high urgency but low impact = still deprioritized | Additive (weighted sum) | Multiplicative can starve items that are moderate in both; accepted tradeoff |
| Visa signals user-confirmed (not AI auto-set) | AI suggests, human confirms | Avoids hallucinated data in critical field (visa affects life decisions); GDPR-safe | Fully automated with confidence threshold | Slower population; correct tradeoff for data this important |
| Funnel maps to existing entities (no new tables) | Query Application + JobOutcome for stage counts | No data duplication; funnel is a view not a store | Dedicated funnel_event table | Query complexity higher; worth it to avoid sync issues |
| OpportunityQueue computed on-demand (not pre-materialized) | Compute at request time from live data | Always fresh; no stale cache; relatively cheap queries (~5 queries) | Pre-compute nightly via scheduler | Stale by end of day; live is better for actions that change hourly |
| Company intelligence as raw facts only | No composite "company intelligence score" | Avoid premature scoring with insufficient signal; user judges from facts | Weighted composite like CompanyPriorityScorer | May add composite later (Plan: "after 500+ companies with outcome data") |
| VisaFriendliness derived deterministically | Simple signal count → enum tier | Transparent, auditable, no hidden ML; user understands exactly why | AI-classified from job descriptions | AI would hallucinate; deterministic is trustworthy |

## Risks (Phases 3-5)

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| AI-generated messages sound generic/robotic | Low reply rates, damages personal brand | Medium | Strong per-variant constraints in profile.yaml; preview/edit flow (never auto-send); track reply rates per variant for feedback |
| Message variant proliferation | Too many variants → user confusion, maintenance burden | Low | Start with 5 core variants; add only when data shows gap; variants must have fundamentally different intent |
| Action queue overwhelm (too many daily actions) | User ignores queue entirely | Medium | Default limit 10 actions/day; strict urgency thresholds; "nothing today" is valid state |
| Funnel data too sparse for valid analysis | AI analysis provides misleading insights | High (early usage) | Min-sample-size guard on effectiveness; AI prompt includes data volume context; suppress analysis below threshold |
| Company enrichment exhausts LinkedIn budget | People discovery starved of quota | Medium | Separate rate limit pool for enrichment (or low-priority slot); enrichment runs on off-peak schedule (Sunday 6am) |
| Visa signal AI suggestions hallucinate | User trusts wrong signals → bad decisions | Low (requires confirmation) | requiresConfirmation always true; evidence field shows reasoning; user can reject; no auto-apply path |
| ActionScorer weights poorly calibrated | Wrong actions prioritized → missed opportunities | Medium | All weights in profile.yaml; adjust based on actual outcome correlation (Phase 4 Step 6); start conservative |
| FINAL_ROUND enum addition breaks existing queries | Existing code may not handle new stage | Low | OutcomeStage uses STRING enum (no ordinal); existing queries filter by known values; new value is additive |

---

## Updated Package Structure (Phases 3-5)

```
api/src/main/java/dev/jobhunter/
├── people/
│   ├── model/                          # (existing from Phase 1-2)
│   ├── repository/                     # (existing)
│   ├── service/
│   │   ├── RelationshipService.java    # (existing — full aggregate)
│   │   ├── ContactDiscoveryService.java # (existing)
│   │   ├── ContactPriorityScorer.java  # (existing, concrete class)
│   │   ├── OutreachMessageGenerator.java    # NEW Phase 3
│   │   ├── OutreachContextAssembler.java    # NEW Phase 3 (concrete, no interface)
│   │   ├── FunnelAggregator.java            # NEW Phase 4
│   │   ├── ActionScorer.java                # NEW Phase 4 (concrete class)
│   │   ├── OpportunityQueue.java            # NEW Phase 4 (concrete class)
│   │   ├── EffectivenessTracker.java        # NEW Phase 4
│   │   ├── CompanyEnrichmentService.java    # NEW Phase 5
│   │   ├── HiringVelocityCalculator.java    # NEW Phase 5
│   │   └── VisaSignalService.java           # NEW Phase 5
│   ├── ai/                                  # NEW Phase 3+4
│   │   ├── AiTask.java                      # Interface (6 impls, well-earned)
│   │   ├── InfoChatTask.java
│   │   ├── TechDiscussionTask.java
│   │   ├── ReferralAskTask.java
│   │   ├── FollowUpTask.java
│   │   ├── RecruiterPitchTask.java
│   │   └── FunnelAnalysisTask.java
│   ├── poster/                             # (existing)
│   │   └── PosterExtractorRegistry.java    # (existing from Phase 1)
│   ├── dto/                                # (existing + new DTOs)
│   │   ├── GeneratedMessageDto.java         # NEW
│   │   ├── ScoredActionDto.java             # NEW
│   │   ├── FunnelDto.java                   # NEW
│   │   ├── FunnelAnalysisDto.java           # NEW
│   │   ├── EffectivenessDto.java            # NEW
│   │   ├── CompanyIntelligenceDto.java      # NEW
│   │   ├── VisaSignalsDto.java              # NEW
│   │   └── VisaSuggestionDto.java           # NEW
│   └── scheduler/
│       ├── ContactDiscoveryScheduler.java   # (existing)
│       └── CompanyEnrichmentScheduler.java  # NEW Phase 5
├── crawl/                                   # REFACTORED
│   ├── PostCrawlPipeline.java               # NEW (extracted from CrawlService)
│   └── PostCrawlHook.java                   # NEW interface
├── controller/
│   ├── PeopleController.java               # (existing)
│   ├── OutreachController.java             # NEW Phase 3
│   ├── PipelineController.java             # NEW Phase 4 (extends existing)
│   └── CompanyIntelligenceController.java  # NEW Phase 5
└── model/enums/
    └── VisaFriendliness.java               # NEW Phase 5
```

---

## Phase Dependency Map

```
Phase 1 (Discovery) ──► Phase 2 (CRM) ──► Phase 3 (AI Outreach)
                                │                    │
                                ▼                    ▼
                         Phase 4 (Pipeline + Queue)
                                │
                                ▼
                         Phase 5 (Company Intel)

Phase 3 requires: OutreachContact, Relationship, RelationshipEvent (Phase 1+2)
Phase 4 requires: RelationshipEvent (Phase 2), InterviewSource (Phase 2), OutreachMessage (Phase 2+3)
Phase 5 requires: Company entity (existing), LinkedIn MCP (existing) — largely independent
```

Phase 5 can technically run in parallel with Phase 3-4 since it extends Company (existing entity) with no dependency on people-specific tables.
