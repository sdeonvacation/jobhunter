# JobHunter - Project AGENTS.md

## Commands

### API (Spring Boot) - run from `api/`

```bash
# Install/build
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew build -x test

# Run dev server
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew bootRun

# Run JAR directly (faster restart)
$JAVA_HOME/bin/java -jar build/libs/jobhunter-api-0.0.1-SNAPSHOT.jar --spring.liquibase.enabled=false --spring.quartz.auto-startup=false

# Build fat JAR
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew bootJar

# Run ALL unit tests (excludes @Tag("integration"))
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew test

# Run single test class
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew test --tests "dev.jobhunter.filter.LanguageFilterImplTest"

# Run integration tests (needs Docker/Testcontainers)
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew integrationTest

# Type check (compile only)
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew compileJava
```

### Dashboard (React) - run from `dashboard/`

```bash
npm install
npm run dev          # Vite on http://localhost:3000, proxies /api → :8080
npm run build        # tsc && vite build
npm test             # vitest run
npm test -- src/test/Companies.test.tsx   # single test file
npx tsc --noEmit     # type check only
```

### MCP Server (TypeScript) - run from `mcp-server/`

```bash
npm install
npm run build        # tsc
npm run dev          # tsx src/index.ts
npm test             # vitest run
npm test -- src/test/tools.test.ts  # single test
npx tsc --noEmit     # type check
```

### Admin Endpoints (manual triggers)

```bash
curl -X POST http://localhost:8080/api/admin/crawl                    # crawl all due endpoints
curl -X POST http://localhost:8080/api/admin/crawl/{endpointId}       # crawl single endpoint
curl -X POST http://localhost:8080/api/admin/score                    # re-score all unscored jobs
curl -X POST http://localhost:8080/api/admin/backfill-descriptions    # backfill SmartRecruiters descriptions
curl http://localhost:8080/api/admin/health                           # endpoint health report
```

## Project Structure

```
jobhunt/
├── api/                         # Spring Boot 3.3.5 backend (Java 21)
│   └── src/main/java/dev/jobhunter/
│       ├── ai/                  # AI providers (Anthropic, OpenAI) - cover letters, resume tailoring
│       ├── config/              # Spring config, CORS, Quartz, WebClient, RetryFilter
│       ├── controller/          # REST controllers (/api/*)
│       ├── discovery/           # Company discovery engine (JobSpy, StepStone MCP)
│       ├── dto/                 # Response DTOs + DtoMapper (static methods, not MapStruct)
│       ├── extraction/          # ATS extractors (Greenhouse, Lever, Ashby, Workday, SmartRecruiters,
│       │                        #   Workable, Personio, Recruitee, JOIN, BambooHR, Breezy, SuccessFactors)
│       ├── filter/              # Job filters: Role, Location, Language (Lingua), YOE, Deduplication
│       ├── model/               # JPA entities + enums
│       ├── repository/          # Spring Data JPA repositories
│       ├── resolution/          # ATS URL resolvers (pattern match, Google search, redirect follow)
│       ├── scheduler/           # Quartz schedulers (Crawl, Scoring, Digest, Discovery, GDPR)
│       ├── scoring/             # Match/Opportunity/CompanyPriority scorers
│       └── service/             # Business logic (CrawlService, PersonalProfileLoader, etc.)
│   └── src/test/java/dev/jobhunter/  # Unit tests (JUnit 5 + WireMock)
│   └── src/main/resources/
│       ├── application.yaml     # Main config (DB, AI, crawl, scoring schedules)
│       └── db/changelog/        # Liquibase migrations
├── dashboard/                   # React 18 + Vite + Tailwind CSS
│   └── src/
│       ├── api/client.ts        # API client (fetch-based)
│       ├── components/          # JobCard, Navigation, ScoreBadge, StatsCard, TechStack
│       ├── pages/               # DailyDigest, Jobs, Applied, Companies, Health
│       ├── types/index.ts       # All TypeScript interfaces
│       └── test/                # Vitest tests
├── mcp-server/                  # MCP TypeScript server (16 tools, 5 resources)
├── plans/                       # Feature plans (PLAN_*.md)
├── design/                      # HLD documents (HLD_*.md)
├── profile.yaml                 # Personal profile + ALL filter/scoring config
└── docker-compose.yml           # PostgreSQL 16
```

## Key Config: profile.yaml

All filter and scoring logic is externalized to `profile.yaml`:

- `skills[]` — profile skills (name, proficiency, category)
- `preferences` — locations, salary, seniority (used by OpportunityScorer)
- `filters.role.include-patterns` — regex for engineering roles to include
- `filters.role.exclude-keywords` — words to blacklist (manager, devops, mlops, etc.)
- `filters.location.germany-cities` — whitelist of German city patterns
- `filters.location.remote-patterns` — regex for remote job locations
- `filters.yoe.max-years` — skip jobs requiring more than N years
- `scoring.skill-weights` — per-skill weight map
- `scoring.skill-variants` — regex patterns for matching each skill in descriptions
- `scoring.primary-skills` — core skills (java, spring boot, kotlin); score capped at `primary-skill-cap` if none matched
- `scoring.thresholds` — APPLY/MAYBE score and match-count thresholds
- `scoring.bonus-signals` — extra keywords that add bonus weight (ai, llm, etc.)

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| API | Java | 21 (Temurin) |
| API | Spring Boot | 3.3.5 |
| API | Gradle | 8.13 |
| API | Hibernate | 6.5.3 |
| API | Jsoup | 1.18.1 |
| API | Quartz | 2.3.2 |
| DB | PostgreSQL | 16 |
| DB | Liquibase | (managed by Spring) |
| Frontend | React | 18.3 |
| Frontend | Vite | 5.2 |
| Frontend | Tailwind CSS | 3.4 |
| Frontend | TypeScript | 5.4 |
| MCP | @modelcontextprotocol/sdk | 1.0 |
| Testing (API) | JUnit 5 + WireMock 3.5 + Testcontainers 1.20 |
| Testing (FE) | Vitest + Testing Library |

## Code Style

### Java (API)

- **Imports**: project imports first (`dev.jobhunter.*`), then framework (`org.springframework.*`), then stdlib (`java.*`). Star imports OK for `jakarta.persistence.*`, `lombok.*`.
- **Models**: JPA entities with Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`). UUID primary keys.
- **DTOs**: Java records. Flat structure for list endpoints, nested for detail.
- **Mapping**: Static methods in `DtoMapper` class (not MapStruct).
- **Controllers**: Constructor injection, no `@Autowired` on fields. Return `ResponseEntity<T>` for error cases, direct return for happy path.
- **Extractors**: Implement `JobExtractor` interface. Return `ExtractionResult` with `List<RawJobData>`. Auto-registered via Spring component scan into `JobExtractorRegistry`.
- **Filters**: Read config from `PersonalProfileLoader.getProfile()` at construction time. Compile patterns once, not per-call.
- **Scoring**: All weights/thresholds/variants from profile.yaml. No hardcoded skill lists in scorer code.
- **Naming**: `camelCase` methods/vars, `PascalCase` classes, package-per-feature under `dev.jobhunter`.
- **Tests**: Mirror source package structure. `@Tag("integration")` for Testcontainers tests. Unit tests use mocks and WireMock.

### TypeScript (Dashboard + MCP)

- **Imports**: React/library imports first, then relative (`../types`, `../api/client`).
- **Types**: Interfaces for data shapes in `types/index.ts`. Props inline or in same file.
- **Components**: Function components only. `export default` for pages, named export for shared components.
- **State**: `useState` + `useEffect` + `useCallback`. No state management library.
- **API calls**: All via `api` object in `api/client.ts`. Responses unwrapped in client, pages get clean data.
- **Styling**: Tailwind utility classes inline. Dark theme with custom tokens in `tailwind.config.js` (surface-900..500, accent, text-primary/secondary/muted).
- **Animations**: CSS-only via Tailwind keyframes. `animate-slide-up` with `animation-fill-mode: both` for stagger patterns.
- **Tests**: Vitest + Testing Library. Test files in `src/test/` directory.

## Runtime Notes

- PostgreSQL runs in Colima (`colima-jobhunt` profile) on port **5435**.
- Start DB: `cd /Users/i570749/projects/jobhunt && docker -c colima-jobhunt compose up -d db`
- DB credentials: `jobhunter/jobhunter` database `jobhunter`.
- API uses launchd plist at `/tmp/dev.jobhunter.api.plist` to stay alive across shell sessions.
- Dashboard uses launchd plist at `/tmp/dev.jobhunter.dashboard.plist`.
- Testcontainers need Colima default profile Docker socket (currently incompatible with Docker 29 API).
- `JAVA_HOME` must point to Temurin 21, not the system Java 25.
- AI config (provider, api-key, base-url, models) in `application.yaml`, overridable via env vars (`JOBHUNTER_AI_PROVIDER`, `JOBHUNTER_AI_API_KEY`, `JOBHUNTER_AI_BASE_URL`).
- AtsType enum: GREENHOUSE, LEVER, LEVER_EU, ASHBY, SMARTRECRUITERS, WORKABLE, WORKDAY, WORKDAY_PROTECTED, PERSONIO, BREEZY, RECRUITEE, JOIN, BAMBOOHR, TEAMTAILOR, SUCCESSFACTORS, ICIMS, JOBVITE, STEPSTONE, ARBEITNOW, INDEED, LINKEDIN, CUSTOM, UNKNOWN.
