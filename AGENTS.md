# JobHub - Project AGENTS.md

## Commands

### API (Spring Boot) - run from `api/`

```bash
# Install/build
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew build -x test

# Run dev server
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew bootRun

# Run JAR directly (faster restart)
$JAVA_HOME/bin/java -jar build/libs/jobhub-api-0.0.1-SNAPSHOT.jar --spring.liquibase.enabled=false --spring.quartz.auto-startup=false

# Build fat JAR
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew bootJar

# Run ALL unit tests (excludes @Tag("integration"))
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew test

# Run single test class
JAVA_HOME=/Users/i570749/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home ./gradlew test --tests "dev.jobhub.filter.LanguageFilterImplTest"

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

## Project Structure

```
jobhunt/
├── api/                         # Spring Boot 3.3.5 backend (Java 21)
│   └── src/main/java/dev/jobhub/
│       ├── ai/                  # AI providers (Anthropic, OpenAI)
│       ├── config/              # Spring config, CORS, Quartz, WebClient
│       ├── controller/          # REST controllers (/api/*)
│       ├── discovery/           # Company discovery engine
│       ├── dto/                 # Response DTOs + DtoMapper
│       ├── extraction/          # ATS extractors (Greenhouse, Lever, Ashby, Workday)
│       ├── filter/              # Language filter (Lingua)
│       ├── model/               # JPA entities + enums
│       ├── repository/          # Spring Data JPA repositories
│       ├── resolution/          # ATS URL resolvers (pattern, Google, redirect)
│       ├── scheduler/           # Quartz job schedulers
│       ├── scoring/             # Match/Opportunity/CompanyPriority scorers
│       └── service/             # Business logic services
│   └── src/test/java/dev/jobhub/  # Unit tests (JUnit 5 + WireMock)
│   └── src/main/resources/
│       ├── application.yaml     # Main config
│       └── db/changelog/        # Liquibase migrations
├── dashboard/                   # React 18 + Vite + Tailwind CSS
│   └── src/
│       ├── api/client.ts        # API client (fetch-based)
│       ├── components/          # Shared UI components
│       ├── pages/               # Route pages (Jobs, Pipeline, Companies, Discovery, Digest)
│       ├── types/index.ts       # All TypeScript interfaces
│       └── test/                # Vitest tests
├── mcp-server/                  # MCP TypeScript server (16 tools, 5 resources)
├── plans/                       # Feature plans (PLAN_*.md)
├── design/                      # HLD documents (HLD_*.md)
├── docker-compose.yml           # PostgreSQL 16
└── profile.yaml                 # Personal job search profile
```

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| API | Java | 21 (Temurin) |
| API | Spring Boot | 3.3.5 |
| API | Gradle | 8.13 |
| API | Hibernate | 6.5.3 |
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

- **Imports**: project imports first (`dev.jobhub.*`), then framework (`org.springframework.*`), then stdlib (`java.*`). Star imports OK for `jakarta.persistence.*`, `lombok.*`.
- **Models**: JPA entities with Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`). UUID primary keys.
- **DTOs**: Java records. Flat structure for list endpoints, nested for detail.
- **Mapping**: Static methods in `DtoMapper` class (not MapStruct).
- **Controllers**: Constructor injection, no `@Autowired` on fields. Return `ResponseEntity<T>` for error cases, direct return for happy path.
- **Naming**: `camelCase` methods/vars, `PascalCase` classes, package-per-feature under `dev.jobhub`.
- **Tests**: Mirror source package structure. `@Tag("integration")` for Testcontainers tests. Unit tests use mocks and WireMock.
- **Error handling**: `Optional` returns from services, controllers map to 404. No global exception handler yet.

### TypeScript (Dashboard + MCP)

- **Imports**: React/library imports first, then relative (`../types`, `../api/client`).
- **Types**: Interfaces for data shapes in `types/index.ts`. Props inline or in same file.
- **Components**: Function components only. `export default` for pages, named export for shared components.
- **State**: `useState` + `useEffect` + `useCallback`. No state management library.
- **API calls**: All via `api` object in `api/client.ts`. Responses unwrapped in client, pages get clean data.
- **Styling**: Tailwind utility classes inline. No CSS modules.
- **Tests**: Vitest + Testing Library. Test files in `src/test/` directory.

## Runtime Notes

- PostgreSQL runs in Colima (`colima-reactive-resume` profile) on port **5434** (not default 5432).
- DB credentials: `jobhub/jobhub` database `jobhub`.
- API uses launchd plist at `/tmp/dev.jobhub.api.plist` to stay alive across shell sessions.
- Testcontainers need Colima default profile Docker socket (currently incompatible with Docker 29 API).
- `JAVA_HOME` must point to Temurin 21, not the system Java 25.
