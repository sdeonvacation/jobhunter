# JobHunter

[![npm](https://img.shields.io/npm/v/jobhunter-mcp)](https://www.npmjs.com/package/jobhunter-mcp)
[![GitHub release](https://img.shields.io/github/v/release/sdeonvacation/jobhunter)](https://github.com/sdeonvacation/jobhunter/releases)

![JobHunter Dashboard](docs/screenshot.png)

**Autonomous job discovery platform that finds, filters, and scores open positions — so you spend time applying, not searching.**

## What It Does

### Discovers jobs automatically
Monitors company career pages across 12+ hiring platforms. New jobs are picked up within hours of being posted — no manual checking required.

### Filters intelligently
Only shows you what matters:
- Roles matching your profession (excludes irrelevant departments)
- Your preferred locations or remote positions
- Postings in your language
- Appropriate experience level
- Deduplicates cross-posted listings

### Scores every job against your profile
Each job gets a match score (0-100) based on how well it fits your skills, weighted by what matters most to you. Jobs are ranked so the best opportunities float to the top.

A "primary skill" gate prevents unrelated roles from getting inflated scores just because they share peripheral tools with your profile.

### Recommends action
Every job gets a clear recommendation:
- **APPLY** — strong match, go for it
- **MAYBE** — partial match, worth a look
- **SKIP** — not a fit

### Daily Digest
Each morning, see exactly what's new: how many jobs were found, how many are worth applying to, and which companies are hiring.

## Key Capabilities

| Capability | Detail |
|-----------|--------|
| Career pages monitored | Configurable, across 12+ ATS platforms |
| Aggregator sources | LinkedIn, Indeed, BerlinStartupJobs, Arbeitnow |
| Crawl frequency | 3x daily (configurable) |
| Scoring | Keyword match + opportunity composite |
| Filters | Role, location, language, experience, dedup |
| AI features | Cover letter generation, resume tailoring, AI page parsing |
| LinkedIn integration | Job search via MCP server, contact discovery, outreach |
| Dashboard | Dark-themed web app with search, filters, applied tracking |
| MCP integration | Query jobs from any AI assistant (Claude, etc.) |

## Supported Hiring Platforms

**ATS platforms:** Greenhouse, Lever, Ashby, Workday, SmartRecruiters, Workable, Personio, Recruitee, JOIN, BambooHR, Breezy, SAP SuccessFactors, TeamTailor, iCIMS, Jobvite.

**Aggregators:** LinkedIn (via MCP scraper), Indeed (via JobSpy), BerlinStartupJobs (AI-parsed), Arbeitnow (REST API).

## How It Works

1. **Configure once** — Define your skills, preferred locations, and what roles to include/exclude in a simple config file.
2. **It runs continuously** — Jobs are crawled, filtered, scored, and ready for you.
3. **Check the dashboard** — Daily Digest shows new opportunities sorted by fit. One click opens the application page.
4. **Track progress** — Mark jobs as applied. See your pipeline at a glance.
5. **Stay informed** — Health page shows which company feeds are working, which are down.

## What Makes It Different

- **Not LinkedIn** — LinkedIn shows you what its algorithm wants you to see, buries relevant roles in spam, and locks insights behind Premium. JobHunter pulls directly from company career pages, scores transparently against YOUR skills, and surfaces everything — no paywall, no "promoted" listings, no recruiter noise.
- **Not another job board** — Pulls directly from company career pages, not aggregators. You see jobs the moment they're posted.
- **Fully configurable** — Every filter, weight, and threshold is tunable. Works for any profession: engineering, design, marketing, finance, legal — just configure your skills and role filters.
- **No account required** — Runs locally. Your profile, preferences, and application history stay on your machine.
- **Extensible** — Adding a new company takes one database entry. Adding a new hiring platform takes one extractor class.

## Quick Start (Docker)

The fastest way to run JobHunter — no Java, Node.js, or build tools needed.

### Prerequisites

- Docker & Docker Compose

### 1. Clone and configure

```bash
git clone https://github.com/sdeonvacation/jobhunter.git
cd jobhunter
```

Edit `profile.yaml` (your skills, filters, scoring) and `keywords.yaml` (keyword extraction patterns). See [Configuration](#configuration) below.

### 2. Start everything

```bash
docker compose up -d
```

This launches:
- **PostgreSQL** on port 5435
- **API** on http://localhost:8080
- **Dashboard** on http://localhost:3000

### 3. Add companies and crawl

```bash
curl -X POST http://localhost:8080/api/admin/crawl
curl -X POST http://localhost:8080/api/admin/score
```

Open http://localhost:3000 — your Daily Digest is ready.

### 4. Install the MCP server

```bash
npm install -g jobhunter-mcp
```

Or use directly without installing:
```bash
npx jobhunter-mcp
```

See [MCP Server](#mcp-server) below for client configuration.

---

## Development Setup

For contributors or those who want to run services individually.

### Prerequisites

- Java 21 (Temurin) — auto-detected from Gradle toolchain or `JAVA_HOME`
- Node.js 18+
- Docker (Docker Desktop or Colima)
- `uvx` (for LinkedIn MCP server — `pip install uvx` or `pipx install uv`)

### 1. Configure

```bash
git clone https://github.com/sdeonvacation/jobhunter.git
cd jobhunter
```

Edit `profile.yaml` and `keywords.yaml` at the project root. See [Configuration](#configuration) below for details.

Set the required environment variable:

```bash
export JOBHUNTER_AI_API_KEY=<your-api-key>           # Anthropic or OpenAI
export JOBHUNTER_AI_PROVIDER=anthropic               # or "openai" (default: anthropic)
```

### 2. Build and start

```bash
make build   # compile API JAR (first time only)
make up      # start entire stack
```

This launches (in order):
- **PostgreSQL** on port 5435
- **LinkedIn MCP** on port 8000
- **API** on http://localhost:8080
- **Dashboard** on http://localhost:3000

### 3. Add companies and trigger a crawl

```bash
curl -X POST http://localhost:8080/api/admin/crawl
curl -X POST http://localhost:8080/api/admin/score
```

Open http://localhost:3000 — your Daily Digest is ready.

### Available Make targets

| Command | Description |
|---------|-------------|
| `make up` | Start all services (DB, MCP, API, Dashboard) |
| `make down` | Stop app services (DB left running) |
| `make restart` | Stop and start all services |
| `make build` | Rebuild the API JAR |
| `make logs` | Tail the API log |
| `make status` | Show which services are running |

### Running services individually

If you prefer to run services manually instead of using the Makefile:

```bash
# Database
docker compose up -d db

# API
cd api && ./gradlew bootRun

# Dashboard
cd dashboard && npm install && npm run dev
```

## MCP Server

The MCP server exposes JobHunter tools to any AI assistant that supports the [Model Context Protocol](https://modelcontextprotocol.io) (Claude, OpenCode, etc.).

### Install

```bash
npm install -g jobhunter-mcp
```

Or run without installing:
```bash
npx jobhunter-mcp
```

For development (from repo):
```bash
cd mcp-server
npm install && npm run build
```

### Client Configuration

For OpenCode (`opencode.json`):

```json
{
  "mcp": {
    "jobhunter": {
      "type": "local",
      "command": ["npx", "jobhunter-mcp"],
      "environment": {
        "JOBHUNTER_API_URL": "http://localhost:8080"
      },
      "enabled": true
    }
  }
}
```

For Claude Code (`.claude/settings.json` or project `.mcp.json`):

```json
{
  "mcpServers": {
    "jobhunter": {
      "command": "npx",
      "args": ["jobhunter-mcp"],
      "env": {
        "JOBHUNTER_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Tools

| Tool | Input | Description |
|------|-------|-------------|
| `get_top_jobs` | `n` (default 10) | Top jobs ranked by match score — best overall opportunities |
| `get_top_jobs_today` | `n` (default 10) | Today's new jobs sorted by match score |
| `get_jobs` | `skill`, `n` (default 10) | Search jobs containing a skill/keyword in their description |
| `get_job_keywords` | `job_id` | Extract keywords from a job (accepts UUID, 8-char short ID, or URL) |
| `mark_job_applied` | `job_id` | Mark a job as applied (accepts UUID or 8-char short ID) |
| `add_company` | `name`, `careers_url` | Register a new company with its careers page URL |

### Typical Workflow

1. **Browse top jobs:**
   ```
   → get_top_jobs(5)
   1. [a3f2c8d1] Senior Designer @ Acme Corp | Berlin | Match: 85 | Opp: 72 | APPLY
   2. [b7e4f9a2] Product Manager @ BigCo | Remote | Match: 78 | Opp: 65 | MAYBE
   ...
   ```

2. **Extract keywords for resume tailoring:**
   ```
   → get_job_keywords("a3f2c8d1")
   Senior Designer @ Acme Corp
   Keywords: Figma, design systems, user research, prototyping, accessibility, ...
   ```

   Or pass a URL directly:
   ```
   → get_job_keywords("https://boards.greenhouse.io/company/jobs/12345")
   Keywords: Python, FastAPI, AWS, Docker, ...
   ```

3. **Mark jobs as applied:**
   ```
   → mark_job_applied("a3f2c8d1")
   Job a3f2c8d1 marked as applied.
   ```

4. **Search by skill:**
   ```
   → get_jobs("figma", 5)
   1. [c9d1e2f3] UX Designer @ StartupX | Berlin | Match: 92 | Opp: 80 | APPLY
   ...
   ```

### Keyword Patterns

Keyword extraction is configured in `keywords.yaml`. See [Configuration](#configuration) below.

## Configuration

All behavior is controlled by two YAML files at the project root. No code changes needed.

### Quick Setup for Your Role

Get running in 3 steps. Edit `profile.yaml` at the project root:

**Step 1: Set your identity**
```yaml
name: Your Name
title: Your Job Title
years-of-experience: 4
```

**Step 2: Define your role filters**

The role filter is whitelist-based. A job title must contain at least one of your `include-patterns` to be kept. Everything else is discarded. Then `exclude-keywords` removes unwanted matches.

```yaml
filters:
  role:
    include-patterns:
      - "developer"        # plain word match (case-insensitive)
      - "software"
      - "backend"
      - "back[\\s-]end"    # matches "back end" and "back-end"
      - "fullstack"
      - "full[\\s-]stack"
      - "\\bjava\\b"       # word boundary prevents matching "javascript"
      - "\\bai\\b"         # word boundary prevents matching "email"
      - "\\bcloud\\b"
      - "cloud[\\s-]native"
      - "entwickler"       # German for developer
    exclude-keywords:
      - "manager"
      - "director"
      - "intern"
      - "student"
```

Patterns are regex (case-insensitive). Use `\\b` for word boundaries on short terms, `[\\s-]` to match space or hyphen variants.

**Step 3: Set your locations**

```yaml
  location:
    target-cities:
      - "berlin"
      - "munich"
      - "remote"
    remote-patterns:
      - "remote"
      - "remote.*germany"
  language:
    target: "en"
    exclude-patterns:
      - "german\\s+c[12]"
      - "fluent\\s+german"
  yoe:
    max-years: 5
```

Restart the API after editing. That's it, the system will now filter jobs to your exact role and location preferences.

---

### Role Examples

<details>
<summary><strong>Backend Engineer (Java/Spring)</strong></summary>

```yaml
name: Sam
title: Backend Engineer
years-of-experience: 4

skills:
  - name: Java
    proficiency: expert
  - name: Spring Boot
    proficiency: expert
  - name: Kubernetes
    proficiency: advanced

filters:
  role:
    include-patterns:
      - "\\bjava\\b"
      - "developer"
      - "software"
      - "backend"
      - "back[\\s-]end"
      - "fullstack"
      - "full[\\s-]stack"
      - "\\bcloud\\b"
      - "cloud[\\s-]native"
      - "engineer"
    exclude-keywords:
      - "manager"
      - "frontend"
      - "designer"
      - "devops"
      - "intern"
  location:
    target-cities: ["berlin", "munich", "hamburg", "remote"]
  yoe:
    max-years: 5

scoring:
  primary-skills: ["java", "spring boot"]
  primary-skill-cap: 70
  skill-weights:
    java: 5.0
    spring boot: 4.5
    kubernetes: 3.0
```
</details>

<details>
<summary><strong>Product Designer</strong></summary>

```yaml
name: Alex
title: Product Designer
years-of-experience: 3

skills:
  - name: Figma
    proficiency: expert
  - name: User Research
    proficiency: advanced
  - name: Design Systems
    proficiency: advanced

filters:
  role:
    include-patterns:
      - "designer"
      - "\\bux\\b"
      - "\\bui\\b"
      - "product\\s+design"
      - "interaction\\s+design"
      - "visual\\s+design"
    exclude-keywords:
      - "engineer"
      - "developer"
      - "manager"
      - "intern"
  location:
    target-cities: ["berlin", "munich", "remote"]
  yoe:
    max-years: 5

scoring:
  primary-skills: ["figma", "user research"]
  primary-skill-cap: 70
  skill-weights:
    figma: 5.0
    user research: 4.5
    design systems: 4.0
```
</details>

<details>
<summary><strong>Data Scientist (Python/ML)</strong></summary>

```yaml
name: Jordan
title: Data Scientist
years-of-experience: 3

skills:
  - name: Python
    proficiency: expert
  - name: PyTorch
    proficiency: advanced
  - name: SQL
    proficiency: expert

filters:
  role:
    include-patterns:
      - "data\\s+scientist"
      - "machine\\s+learning"
      - "\\bml\\b"
      - "\\bai\\b"
      - "artificial\\s+intelligence"
      - "deep\\s+learning"
      - "\\bnlp\\b"
      - "research\\s+engineer"
    exclude-keywords:
      - "manager"
      - "director"
      - "intern"
      - "analyst"
  location:
    target-cities: ["san francisco", "new york", "remote"]
    remote-patterns: ["remote", "remote.*us"]
  language:
    target: "en"
  yoe:
    max-years: 5

scoring:
  primary-skills: ["python", "pytorch", "machine learning"]
  primary-skill-cap: 70
  skill-weights:
    python: 5.0
    pytorch: 4.5
    sql: 3.0
```
</details>

<details>
<summary><strong>DevOps / Platform Engineer</strong></summary>

```yaml
name: Riley
title: Platform Engineer
years-of-experience: 5

skills:
  - name: Kubernetes
    proficiency: expert
  - name: Terraform
    proficiency: expert
  - name: AWS
    proficiency: advanced

filters:
  role:
    include-patterns:
      - "devops"
      - "dev\\s*ops"
      - "platform"
      - "infrastructure"
      - "\\bsre\\b"
      - "site\\s+reliability"
      - "\\bcloud\\b"
      - "kubernetes"
    exclude-keywords:
      - "manager"
      - "director"
      - "intern"
      - "frontend"
  location:
    target-cities: ["london", "amsterdam", "remote"]
    remote-patterns: ["remote", "remote.*eu"]
  yoe:
    max-years: 7

scoring:
  primary-skills: ["kubernetes", "terraform", "aws"]
  primary-skill-cap: 70
  skill-weights:
    kubernetes: 5.0
    terraform: 4.5
    aws: 4.0
```
</details>

---

### `profile.yaml` — Full Reference

| Section | What it configures |
|---------|-------------------|
| `name`, `title`, `years-of-experience` | Your identity for cover letters and resume tailoring |
| `skills[]` | Each skill with proficiency level and category |
| `preferences` | Locations, salary, seniority, remote preference |
| `filters.role.include-patterns` | Whitelist: job title must match at least one (regex, case-insensitive) |
| `filters.role.exclude-keywords` | Blacklist: title matching any of these is rejected (takes priority) |
| `filters.location.target-cities` | Allowed city names (case-insensitive substring match) |
| `filters.location.remote-patterns` | Regex patterns that identify remote positions |
| `filters.language.target` | ISO language code (e.g., "en") |
| `filters.language.exclude-patterns` | Regex patterns indicating non-target language requirement |
| `filters.yoe.max-years` | Maximum years of experience to consider |
| `scoring.skill-weights` | Per-skill weight for match scoring |
| `scoring.skill-variants` | Regex patterns for recognizing each skill in JDs |
| `scoring.primary-skills` | Core skills — score capped if none match |
| `scoring.thresholds` | Score cutoffs for APPLY/MAYBE/SKIP recommendations |

Restart the API after editing.

### `keywords.yaml` — Keyword Extraction Patterns

Controls what the `get_job_keywords` MCP tool extracts from job descriptions. Each category contains regex patterns that match against JD text.

**Customize this with YOUR skill set.** Add the tools, methodologies, and domain terms relevant to your profession so extracted keywords align with what matters for your resume tailoring.

The default `keywords.yaml` ships with software engineering terms, but you can replace or extend it for any field:

| Profession | Example categories you might add |
|-----------|----------------------------------|
| Software Engineer | languages, frameworks, cloud, databases, architecture |
| Product Designer | design tools, methodologies, deliverables, research methods |
| Data Scientist | ML frameworks, statistical methods, data tools, visualization |
| Marketing | platforms, analytics tools, methodologies, channels |
| Finance | financial models, regulations, tools, certifications |

Patterns support regex syntax:
- `\w*` — suffix wildcard (`deploy\w*` matches deployment, deploying, deployed)
- `\s*` — optional space (`Spring\s*Boot` matches "Spring Boot" and "SpringBoot")
- `[- ]` — dash or space (`event[- ]driven` matches both forms)
- `?` — optional char (`OAuth2?` matches OAuth and OAuth2)

Example — adding design terms:
```yaml
design_tools:
  - Figma
  - Sketch
  - Adobe\s*XD
  - Framer
  - Miro

methodologies:
  - design\s*thinking
  - user\s*research
  - usability\s*test\w*
  - A\/B\s*test\w*
  - accessibility
  - WCAG
```

Changes take effect on next MCP tool invocation (no rebuild needed).
