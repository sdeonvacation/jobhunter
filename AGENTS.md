# JobHunter - Project AGENTS.md

## Commands

### Java environment (read first)

`JAVA_HOME` must be JDK 21. The system JDK is newer, so Gradle fails without it.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# -> /opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
```

Notes:
- `$HOME/.gradle/jdks` does **not** exist on this machine. `Makefile` and
  `scripts/dev.sh` probe it first and fall back to `/usr/libexec/java_home -v 21`,
  which is what actually resolves.
- The JDK is Homebrew `openjdk@21`, not Temurin.

### API (Spring Boot) - run from `api/`

Build file is `api/build.gradle.kts`.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Install/build
./gradlew build -x test

# Run dev server (Gradle)
./gradlew bootRun

# Run JAR directly (faster restart)
"$JAVA_HOME/bin/java" -jar build/libs/jobhunter-api-0.0.1-SNAPSHOT.jar --spring.liquibase.enabled=false --spring.quartz.auto-startup=false

# Build fat JAR
./gradlew bootJar

# Run ALL unit tests (excludes @Tag("integration"))
./gradlew test

# Run single test class
./gradlew test --tests "dev.jobhunter.filter.LanguageFilterImplTest"

# Run integration tests (needs Docker/Testcontainers)
./gradlew integrationTest

# Type check (compile only)
./gradlew compileJava
```

Note: `api/build.gradle.kts` filters several test sources out of `compileTestJava`
(in-flight production code — `people/**` tests, `CrawlService`, `CliStrategy`,
`linkedin/LinkedInDescriptionEnricherTest`). Those classes are silently skipped by
`./gradlew test` until the filter is removed.

### Dashboard (React) - run from `dashboard/`

```bash
npm install
npm run dev          # Vite on http://localhost:3000, proxies /api → VITE_API_URL or http://localhost:8089
npm run build        # tsc && vite build
npm run preview      # vite preview
npm test             # vitest run
npm test -- src/test/Companies.test.tsx   # single test file
npx tsc --noEmit     # type check only
```

### MCP Server (TypeScript) - run from `mcp-server/`

This is the **JobHunter MCP server** (stdio). It is not started by `make dev`.

```bash
npm install
npm run build        # tsc
npm run start        # node dist/index.js
npm run dev          # tsx src/index.ts
npm test             # vitest run
npm test -- src/test/tools.test.ts  # single test
npx tsc --noEmit     # type check
```

### Dev stack (Makefile)

```bash
make dev         # DB + LinkedIn MCP + API + Dashboard
make restart     # stop, then `dev` — does NOT rebuild the JAR (see below)
make stop        # stop API/Dashboard/MCP (DB left running)
make status      # what is running, incl. the API's current port
make logs        # tail /tmp/jobhunter/api.log
make logs-all    # tail api.log + dashboard.log + mcp.log
make build       # build-api
make build-api   # gradlew bootJar -x test
make build-dashboard
make test        # gradlew test (API)
make clean       # gradlew clean (API)
```

**`make restart` does NOT rebuild the JAR.** `scripts/dev.sh:68` only builds when
the JAR is missing (`if [[ ! -f "$API_JAR" ]]`), so a restart happily runs stale
bytecode. To pick up Java changes:

```bash
make stop && make build-api && make dev
```

Stop first — rebuilding while the JVM is running has caused JAR corruption.

### Admin Endpoints (manual triggers)

Dev-stack API listens on **8089** (`api/src/main/resources/application.yaml`).
The live port is also written to `/tmp/jobhunter-api.port` by `scripts/dev.sh`.

```bash
PORT=$(cat /tmp/jobhunter-api.port 2>/dev/null || echo 8089)

curl -X POST http://localhost:$PORT/api/admin/crawl                    # crawl all due endpoints
curl -X POST http://localhost:$PORT/api/admin/crawl/{endpointId}       # crawl single endpoint
curl -X POST http://localhost:$PORT/api/admin/score                    # re-score all unscored jobs
curl -X POST http://localhost:$PORT/api/admin/backfill-descriptions    # backfill SmartRecruiters descriptions
curl http://localhost:$PORT/api/admin/health                           # endpoint health report
```

## Project Structure

```
jobhunter/
├── api/                         # Spring Boot 3.3.5 backend (Java 21, build.gradle.kts)
│   └── src/main/java/dev/jobhunter/
│       ├── ai/                  # AI providers - cover letters, resume tailoring
│       ├── config/              # Spring config, CORS, Quartz, WebClient, RetryFilter
│       ├── controller/          # REST controllers (/api/*), incl. AdminController
│       ├── discovery/           # Company discovery engine
│       ├── dto/                 # Response DTOs + DtoMapper (static methods, not MapStruct)
│       ├── filter/              # Job filters: Role, Location, Language (Lingua), YOE, Deduplication
│       ├── indeed/              # Indeed source integration
│       ├── ingestion/           # Aggregator ingestion pipeline, StrategyRegistry,
│       │                        #   AggregatorDescriptionEnricher, DescriptionBackfiller
│       ├── linkedin/            # LinkedIn integration
│       ├── mcp/                 # MCP client wiring (LinkedIn MCP)
│       ├── model/               # JPA entities
│       │   └── enums/           # enums, incl. AtsType
│       ├── people/              # Career-ops / outreach subsystem
│       │   ├── ai/              #   outreach task generators (ReferralAskTask, RecruiterPitchTask, ...)
│       │   ├── crawl/           #   PostCrawlPipeline + PostCrawlHook
│       │   ├── dto/             #   PeopleDtoMapper + contact/funnel/outreach DTOs
│       │   ├── model/           #   GeneratedMessage, OutreachContext, MessageVariant
│       │   ├── poster/          #   recruiter-post extraction
│       │   ├── repository/
│       │   ├── scheduler/       #   FollowUpTask, FunnelAnalysisTask, InfoChatTask
│       │   └── service/         #   FunnelAggregator, OpportunityQueue, ContactPriorityScorer,
│       │                        #   OutreachMessageGenerator, RelationshipService, ActionScorer,
│       │                        #   ContactDiscoveryService, HiringVelocityCalculator, ...
│       ├── repository/          # Spring Data JPA repositories
│       ├── resolution/          # ATS URL resolvers (pattern match, Google search, redirect follow)
│       ├── scheduler/           # Quartz schedulers (Crawl, Scoring, Digest, Discovery, GDPR)
│       ├── scoring/             # Match/Opportunity/CompanyPriority scorers
│       ├── service/             # Business logic (CrawlService, PersonalProfileLoader, etc.)
│       ├── source/              # Configurable sources (IndeedSource, LinkedInSource,
│       │                        #   SourceConfig, DynamicSourceConfigLoader)
│       ├── strategy/            # Fetch strategies
│       │   ├── ats/             #   per-ATS: Greenhouse, Lever, Ashby, Workday, SmartRecruiters,
│       │   │                    #     Workable, Personio, Recruitee, JOIN, BambooHR, Breezy,
│       │   │                    #     SuccessFactors, Teamtailor, Pinpoint, ScreenLoop, Phenom
│       │   ├── direct/          #   AiPageStrategy
│       │   └── aggregator/      #   AiAggregatorStrategy, BuiltInEurope, Cli, GlobalMove,
│       │                        #     Instaffo, Jobgether, Mcp
│       └── util/
│   └── src/test/java/dev/jobhunter/    # Unit tests (JUnit 5 + WireMock), mirrors main packages
│   └── src/main/resources/
│       ├── application.yaml     # Main config (DB, AI, crawl, scoring, server.port)
│       └── db/changelog/        # Liquibase migrations
├── dashboard/                   # React 18 + Vite + Tailwind CSS
│   └── src/
│       ├── api/client.ts        # API client (fetch-based)
│       ├── components/          # JobCard, Navigation, ScoreBadge, StatsCard, TechStack,
│       │                        #   DigestRecruiterBlock, EvaluationBadge, LivenessBadge, VisaBadge
│       ├── pages/               # Today, Digest, DailyDigest, Jobs, JobDetail, Evaluate, Applied,
│       │                        #   Pipeline, FollowUps, People, ContactDetail, Companies,
│       │                        #   Discovery, Analytics, StoryBank, InterviewPrep, CoverLetter, Health
│       ├── types/index.ts       # All TypeScript interfaces
│       └── test/                # Vitest tests
├── mcp-server/                  # JobHunter MCP server (TS, stdio): 19 tools, 2 resources
│   └── src/{tools,resources,__tests__}/, client.ts, index.ts
├── scrapers/                    # scrape_jobs.py
├── scripts/                     # dev.sh, start-api.sh, start-dashboard.sh,
│                                #   backup-db.sh, globalmove-login.sh
├── infra/                       # launchd plist templates (api, dashboard, linkedin-mcp)
├── requirements/                # REQUIREMENT_*.md specs (aggregators, CSB, globalmove, ...)
├── plans/                       # Feature plans (PLAN_*.md)
├── design/                      # HLD documents (HLD_*.md)
├── docs/                        # screenshots and misc docs
├── cover-letters/               # Generated cover letters
├── profile.yaml                 # Personal profile + ALL filter/scoring config
├── keywords.yaml                # Technology keyword taxonomy (languages, frameworks, databases, ...)
├── docker-compose.yml           # db (Postgres 16), linkedin-mcp, api, dashboard
├── Makefile                     # dev stack entry points
└── README.md
```

## Key Config

### profile.yaml

All filter and scoring logic is externalized here. Top-level keys:

- `name`, `title`, `years-of-experience`
- `skills[]` — profile skills (name, proficiency, category)
- `preferences` — `locations`, `employment-type`, `min-salary-eur`, `seniority`, `languages`, `excluded-industries`
- `filters.role.include-patterns` — regex for engineering roles to include
- `filters.role.exclude-keywords` — words to blacklist (manager, devops, mlops, etc.)
- `filters.location.remote-patterns` — regex for remote job locations
- `filters.location.unknown-action` — what to do when a location cannot be resolved (`skip`)
- `filters.language.*` — `target`, `detect-languages`, `confidence-threshold`, `soft-qualifier-patterns`, `exclude-patterns`
- `filters.yoe.max-years` — skip jobs requiring more than N years
- `filters.visa-sponsorship.*` — `target-countries`, `de-patterns`, `remote-eu-patterns`, `positive-patterns`, `negative-patterns`, `unknown-action`, `ai-fallback`. **`target-countries` is the authoritative target-country set**: `CityCountryResolver` reads it (`CityCountryResolver:130-135`) and `LocationFilterImpl` keeps a job only if the resolved ISO country is a target. There is **no city whitelist** — city/country resolution goes through `CityCountryResolver` (GeoNames CSV + built-in countries).
- `scoring.benchmark-weight`, `scoring.bonus-weight` — global weights
- `scoring.skill-weights` — per-skill weight map
- `scoring.skill-variants` — regex patterns for matching each skill in descriptions
- `scoring.primary-skills` — core skills (java, spring boot, kotlin); score capped at `primary-skill-cap` if none matched
- `scoring.competing-languages`, `scoring.competing-language-cap` — languages that cap the score
- `scoring.thresholds` — APPLY/MAYBE score and match-count thresholds
- `scoring.bonus-signals` — extra keywords that add bonus weight (ai, llm, etc.)
- `scoring.seniority-discount` — `enabled`, `keywords`, `multiplier`
- `people` — career-ops / outreach config
- `filter-profiles` — named filter presets
- `source-filter-overrides` — per-source filter overrides

### keywords.yaml

Technology keyword taxonomy, grouped into `languages`, `frameworks`, `databases`,
`architecture`, `operations`, `security`, `runtime`, `practices`, `collaboration`,
`tools`. Used for keyword extraction and matching.

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| API | Java | 21 (Homebrew openjdk@21) |
| API | Spring Boot | 3.3.5 |
| API | Gradle | 8.13 (wrapper) |
| API | Hibernate | 6.5.3 (via Boot BOM) |
| API | Jsoup | 1.18.1 |
| API | Lingua (language detection) | 1.2.2 |
| API | Quartz | 2.3.2 (via Boot BOM, `spring-boot-starter-quartz`) |
| DB | PostgreSQL | 16 (alpine) |
| DB | Liquibase | managed by Spring |
| Frontend | React | 18.3 |
| Frontend | Vite | 5.2 |
| Frontend | Tailwind CSS | 3.4 |
| Frontend | TypeScript | 5.4 |
| Frontend | Vitest | 1.4 |
| MCP | @modelcontextprotocol/sdk | ^1.0.0 |
| MCP | TypeScript / Vitest | 5.4 / 1.6 |
| Testing (API) | JUnit 5 + WireMock 3.5.4 (standalone) + Testcontainers 1.20.4 |

Additional API deps: `spring-boot-starter-web`, `-data-jpa`, `-webflux`, `-quartz`,
`-actuator`, `spring-boot-testcontainers`.

## Code Style

### Java (API)

- **Imports**: project imports first (`dev.jobhunter.*`), then framework (`org.springframework.*`), then stdlib (`java.*`). Star imports OK for `jakarta.persistence.*`, `lombok.*`.
- **Models**: JPA entities with Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`). UUID primary keys. Enums live in `model/enums/`.
- **DTOs**: Java records. Flat structure for list endpoints, nested for detail. Static mappers (`DtoMapper`, `people/dto/PeopleDtoMapper`), not MapStruct.
- **Controllers**: Constructor injection, no `@Autowired` on fields. Return `ResponseEntity<T>` for error cases, direct return for happy path.
- **Fetch strategies**: implement `FetchStrategy` under `strategy/ats|direct|aggregator/`. ATS board scrapers subclass `AbstractAtsStrategy`. Discovered via Spring component scan into `StrategyRegistry` (`ingestion/`).
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

## Getting the Application Running

### Prerequisites

- Colima default profile running (`colima start`)
- JDK 21 resolvable via `/usr/libexec/java_home -v 21`
- `~/.zshenv` should hold the `JOBHUNTER_AI_*` env vars (the dev stack sources it)

### Step 1: Start PostgreSQL

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock docker compose up -d db
```

Wait for healthy status:
```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock docker compose logs db --tail 3
# Should show: "database system is ready to accept connections"
```

### Step 2: Start the full dev stack (preferred)

```bash
make dev
```

That runs `scripts/dev.sh dev`, which starts in order: **DB → LinkedIn MCP → API →
Dashboard**. Each service is registered with `launchctl submit` (not plain
background processes, not plist files). Logs land in `/tmp/jobhunter/`.

The script:
- Sources `~/.zshenv` for `JOBHUNTER_AI_*` env vars
- Runs the fat JAR directly (not `gradlew bootRun`)
- Reads the API port out of the startup log and writes it to
  `/tmp/jobhunter-api.port`
- Writes `dashboard/.env` with `VITE_API_URL=http://localhost:<port>` so the Vite
  proxy follows whatever port the API actually bound
- Waits for API readiness before starting the dashboard

Ports once up:

| Service | URL / port |
|---------|-----------|
| API | http://localhost:8089 (`/tmp/jobhunter-api.port`) |
| Dashboard | http://localhost:3000 |
| LinkedIn MCP | http://localhost:8000 (`/mcp`) |
| PostgreSQL | localhost:5435 |

Check state with `make status` (it reports the API's real port).

### Step 3: Alternative — Docker Compose (full stack in containers)

`docker-compose.yml` defines four services:

| Service | Image / build | Host port |
|---------|---------------|-----------|
| `db` | `postgres:16-alpine` | 5435 → 5432 |
| `linkedin-mcp` | `stickerdaniel/linkedin-mcp-server:latest` | 8000 → 8000 |
| `api` | `./api` (Dockerfile) | 8081 → 8080 |
| `dashboard` | `./dashboard` (Dockerfile) | 3003 → 80 |

The `api` container mounts `profile.yaml` and `keywords.yaml` read-only and takes
its AI key from `${JOBHUNTER_AI_API_KEY}`.

### Step 4: Populate today's data (optional)

```bash
PORT=$(cat /tmp/jobhunter-api.port 2>/dev/null || echo 8089)
curl -X POST http://localhost:$PORT/api/admin/crawl    # crawl all endpoints
curl -X POST http://localhost:$PORT/api/admin/score    # score new jobs
```

## Runtime Notes

### Docker / Colima

- Uses the **Colima default profile** (shared with other applications — do NOT stop, restart, delete, or modify this profile without checking impact on other apps).
- Docker socket: `unix://$HOME/.colima/default/docker.sock`
- Set `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` for all docker/compose commands.
- PostgreSQL exposed on port **5435** (host) → 5432 (container).
- Compose volume is declared as `pgdata` in the compose project `jobhunter`, i.e.
  the Docker volume `jobhunter_pgdata` (persistent data, do NOT delete).
- DB was originally initialized with user `jobhub`; role `jobhunter` was added later. Both roles exist.

### DB Credentials

- User: `jobhunter`, Password: `jobhunter`, Database: `jobhunter`, Port: `5435`
- Legacy data also accessible via user `jobhub` in database `jobhub`.

### Launchd Services

`scripts/dev.sh` manages these via `launchctl submit` / `launchctl bootout`
(labels, not plist paths). `infra/*.plist.template` holds optional standalone
templates.

| Label | Purpose |
|-------|---------|
| `dev.jobhunter.api` | Spring Boot API (`scripts/start-api.sh`), port 8089 |
| `dev.jobhunter.dashboard` | Vite dev server (`scripts/start-dashboard.sh`), port 3000 |
| `dev.jobhunter.mcp` | LinkedIn MCP via `uvx mcp-server-linkedin@latest`, port 8000 |

Stop a single service, e.g.:
```bash
launchctl bootout gui/$(id -u)/dev.jobhunter.api
```

### Other Notes

- `JAVA_HOME` must point to JDK 21 (Homebrew `openjdk@21` via
  `/usr/libexec/java_home -v 21`), not the system JDK.
- AI config (provider, api-key, base-url, models) in `application.yaml`, overridable via env vars (`JOBHUNTER_AI_PROVIDER`, `JOBHUNTER_AI_API_KEY`, `JOBHUNTER_AI_BASE_URL`).
- Two distinct MCP servers exist: the **JobHunter MCP server** in `mcp-server/`
  (stdio, 19 tools, started by whatever client registers it) and the **LinkedIn MCP
  server** started by `make dev` on port 8000.
- AtsType enum (`model/enums/AtsType.java`): GREENHOUSE, LEVER, LEVER_EU, ASHBY, SMARTRECRUITERS, WORKABLE, WORKDAY, WORKDAY_PROTECTED, PERSONIO, BREEZY, RECRUITEE, JOIN, BAMBOOHR, TEAMTAILOR, SUCCESSFACTORS, ICIMS, JOBVITE, PINPOINT, SCREENLOOP, PHENOM, STEPSTONE, ARBEITNOW, INDEED, LINKEDIN, CUSTOM, UNKNOWN.
- Do not write random corrections to memory. Persist only high-value, reusable facts.

### Skills

- tailor-resume skill is present at ~/.config/opencode/skills/tailor-resume/SKILL.md
- recruiter-review skill is present at ~/.config/opencode/skills/recruiter-review/SKILL.md