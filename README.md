# JobHunter

[![npm](https://img.shields.io/npm/v/jobhunter-mcp)](https://www.npmjs.com/package/jobhunter-mcp)
[![GitHub release](https://img.shields.io/github/v/release/sdeonvacation/jobhunter)](https://github.com/sdeonvacation/jobhunter/releases)

![JobHunter Dashboard](docs/screenshot.png)

**Autonomous job discovery platform that finds, filters, and scores open positions — so you spend time applying, not searching.**

JobHunter monitors company career pages directly, filters out irrelevant roles, and scores every job against your skill profile. Open the dashboard each morning and you'll find a ranked list of opportunities worth your time — no scrolling through LinkedIn, no missed postings.

---

## How It Works

1. **Configure once** — describe your skills, target locations, and role preferences in `profile.yaml`
2. **It runs continuously** — jobs are crawled from company career pages and aggregators, filtered, scored, and persisted
3. **Check the dashboard** — Daily Digest shows new opportunities ranked by fit; one click opens the application
4. **Track progress** — mark jobs applied, see your pipeline at a glance

---

## Quick Start

### Prerequisites

- Docker and Docker Compose
- An AI API key (Anthropic or Google Gemini) — used for AI-powered features and some scrapers

### 1. Clone

```bash
git clone https://github.com/sdeonvacation/jobhunter.git
cd jobhunter
```

### 2. Configure your profile

Edit `profile.yaml` at the project root. At minimum, set your identity, role filters, and location preferences. See [Configuration](#configuration) for the full reference and role examples.

### 3. Set your AI key

```bash
export JOBHUNTER_AI_API_KEY=your-api-key
export JOBHUNTER_AI_PROVIDER=anthropic   # or "openai" for Gemini/OpenAI-compatible
```

### 4. Start

```bash
docker compose up -d
```

This launches:
- **PostgreSQL** on port 5435
- **LinkedIn MCP** on port 8000 (requires first-time auth — see [LinkedIn Setup](#linkedin-setup))
- **API** on http://localhost:8081
- **Dashboard** on http://localhost:3003

### 5. Trigger your first crawl

```bash
curl -X POST http://localhost:8081/api/admin/crawl
curl -X POST http://localhost:8081/api/admin/score
```

Open http://localhost:3003 — your Daily Digest is ready.

---

## Adding Companies

Jobs are discovered from two sources:

**1. Company career pages (primary)** — direct scraping from company ATS systems. Add a company via the dashboard or API:

```bash
curl -X POST http://localhost:8081/api/companies \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "careersUrl": "https://acme.com/careers"}'
```

JobHunter auto-detects the ATS platform (Greenhouse, Lever, Ashby, Workday, and [12+ more](#supported-platforms)) and sets up crawling.

**2. Aggregators (supplemental)** — LinkedIn, Indeed, BerlinStartupJobs, Arbeitnow. Configured in `application.yaml` under `aggregator.sources`. Run on every crawl cycle.

---

## LinkedIn Setup

LinkedIn job search requires a one-time browser authentication. The `linkedin-mcp` container handles this automatically using a headless browser — you just need to provide credentials.

```bash
# Set credentials before starting
export LINKEDIN_EMAIL=you@example.com
export LINKEDIN_PASSWORD=yourpassword

docker compose up -d linkedin-mcp
```

On first start, the container authenticates and saves a session to `~/.linkedin-mcp`. Subsequent starts reuse it.

If authentication fails, restart the container — it will re-authenticate automatically.

> **Note:** LinkedIn scraping is for personal use only. Respect LinkedIn's Terms of Service.

---

## MCP Server

The MCP server exposes JobHunter to any AI assistant that supports the [Model Context Protocol](https://modelcontextprotocol.io) (Claude, OpenCode, Cursor, etc.).

### Install

```bash
npm install -g jobhunter-mcp
```

Or run without installing:
```bash
npx jobhunter-mcp
```

### Configure your AI client

**Claude Code** (`.claude/settings.json` or project `.mcp.json`):
```json
{
  "mcpServers": {
    "jobhunter": {
      "command": "npx",
      "args": ["jobhunter-mcp"],
      "env": { "JOBHUNTER_API_URL": "http://localhost:8081" }
    }
  }
}
```

**OpenCode** (`opencode.json`):
```json
{
  "mcp": {
    "jobhunter": {
      "type": "local",
      "command": ["npx", "jobhunter-mcp"],
      "environment": { "JOBHUNTER_API_URL": "http://localhost:8081" },
      "enabled": true
    }
  }
}
```

### Available tools

| Tool | Description |
|------|-------------|
| `get_top_jobs` | Best overall opportunities ranked by match score |
| `get_top_jobs_today` | Today's new jobs sorted by match score |
| `get_jobs` | Search by skill or keyword |
| `get_job_description` | Full job description text |
| `get_job_keywords` | Tech keywords extracted from a job (for resume tailoring) |
| `mark_job_applied` | Mark a job as applied |
| `add_company` | Register a new company career page |
| `find_contacts` | Find recruiters/hiring managers at a company |
| `connect_with` | Send a LinkedIn connection request |

Jobs are identified by UUID or 8-character short ID (shown on the dashboard). Tools also accept a full job URL for `get_job_keywords`.

### Example session

```
get_top_jobs(5)
→ 1. [a3f2c8d1] Senior Backend Engineer @ Acme Corp | Berlin | APPLY (match: 85)
  2. [b7e4f9a2] Platform Engineer @ BigCo | Remote | APPLY (match: 78)

get_job_keywords("a3f2c8d1")
→ Java, Spring Boot, Kubernetes, Kafka, AWS, PostgreSQL, microservices, ...

mark_job_applied("a3f2c8d1")
→ Marked as applied.
```

---

## Configuration

All behavior is controlled by two YAML files at the project root. No code changes needed.

### `profile.yaml`

Defines who you are, what roles you want, and how jobs are scored.

#### Minimal setup — 3 required sections

**Your identity** (used for cover letters and AI tailoring):
```yaml
name: Sam
title: Backend Engineer
years-of-experience: 4
```

**Role filter** — whitelist of title patterns to keep, then blacklist to reject:
```yaml
filters:
  role:
    include-patterns:
      - "developer"
      - "engineer"
      - "backend"
      - "back[\\s-]end"
      - "\\bjava\\b"       # \\b = word boundary, prevents matching "javascript"
      - "fullstack"
    exclude-keywords:
      - "manager"
      - "director"
      - "intern"
      - "devops"
```

Patterns are case-insensitive regex. A job title must match at least one `include-pattern` AND zero `exclude-keywords` to be kept.

**Location and experience:**
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
      - "german\\s+c[12]"       # exclude roles requiring C1/C2 German
      - "fluent\\s+german"
  yoe:
    max-years: 5
```

**Restart the API after editing.**

#### Scoring (optional)

```yaml
scoring:
  primary-skills: ["java", "spring boot", "kotlin"]
  primary-skill-cap: 70         # max match score if none of these appear in the JD
  skill-weights:
    java: 5.0
    spring boot: 4.5
    kubernetes: 3.0
    aws: 2.5
  thresholds:
    apply-match: 60
    maybe-match: 40
```

`primary-skills` act as a gate — if none appear in a job description, the match score is capped. This prevents peripheral tools from inflating the score on clearly unrelated roles.

---

### Role examples

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
      - "deep\\s+learning"
      - "research\\s+engineer"
    exclude-keywords:
      - "manager"
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
      - "kubernetes"
    exclude-keywords:
      - "manager"
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

### `profile.yaml` full reference

| Section | What it controls |
|---------|-----------------|
| `name`, `title`, `years-of-experience` | Identity for cover letters and AI tailoring |
| `skills[]` | Each skill: `name`, `proficiency` (expert/advanced/intermediate), `category` |
| `preferences` | Salary expectations, seniority, employment type |
| `filters.role.include-patterns` | Whitelist: title must match at least one (regex) |
| `filters.role.exclude-keywords` | Blacklist: title matching any of these is rejected |
| `filters.location.target-cities` | Allowed city names (case-insensitive substring) |
| `filters.location.remote-patterns` | Regex patterns that identify remote positions |
| `filters.language.target` | ISO language code (`"en"`, `"de"`, etc.) |
| `filters.language.exclude-patterns` | Reject jobs requiring a language level (e.g. C2 German) |
| `filters.yoe.max-years` | Skip jobs requiring more than N years of experience |
| `scoring.skill-weights` | Per-skill weight for match scoring |
| `scoring.skill-variants` | Regex patterns for recognizing each skill in JDs |
| `scoring.primary-skills` | Core skills — score capped at `primary-skill-cap` if none match |
| `scoring.thresholds` | Score cutoffs for APPLY / MAYBE / SKIP recommendations |

---

### `keywords.yaml`

Controls what the `get_job_keywords` MCP tool extracts from job descriptions. Each category contains regex patterns matched against JD text.

Customize this with your own skill set. The default ships with software engineering terms, but any profession works:

```yaml
design_tools:
  - Figma
  - Sketch
  - Adobe\s*XD

methodologies:
  - design\s*thinking
  - user\s*research
  - usability\s*test\w*
  - A\/B\s*test\w*
```

Changes take effect on the next MCP tool invocation — no restart needed.

---

## Supported Platforms

**ATS platforms (direct career page scraping):**
Greenhouse, Lever, Ashby, Workday, SmartRecruiters, Workable, Personio, Recruitee, JOIN, BambooHR, Breezy, SAP SuccessFactors, TeamTailor, iCIMS, Jobvite, Pinpoint

**Aggregators:**
LinkedIn (via MCP scraper), Indeed (via JobSpy), BerlinStartupJobs (AI-parsed), Arbeitnow (REST API)

---

## Admin Endpoints

Useful for manual triggers and monitoring:

```bash
curl -X POST http://localhost:8081/api/admin/crawl          # crawl all company pages
curl -X POST http://localhost:8081/api/admin/score          # score all unscored jobs
curl -X POST http://localhost:8081/api/admin/crawl/aggregators  # run aggregators only
curl      http://localhost:8081/api/admin/health            # service health report
```

---

## Development Setup

For contributors or those running services outside Docker.

### Prerequisites

- Java 21 (Temurin)
- Node.js 18+
- Docker (PostgreSQL)
- `uvx` — for LinkedIn MCP server (`pip install uv`)

### Start

```bash
# Set AI provider credentials
export JOBHUNTER_AI_API_KEY=your-key
export JOBHUNTER_AI_PROVIDER=openai    # or anthropic

make build   # build API JAR (first time only)
make dev     # start DB + MCP + API + Dashboard
```

Services start on:
- API: http://localhost:8080
- Dashboard: http://localhost:3000
- LinkedIn MCP: http://localhost:8000

### Make targets

| Command | Description |
|---------|-------------|
| `make dev` | Start all services |
| `make stop` | Stop app services (DB left running) |
| `make restart` | Stop and start all services |
| `make build` | Rebuild the API JAR |
| `make test` | Run API unit tests |
| `make logs` | Tail the API log |
| `make status` | Show which services are running |

### Running services individually

```bash
# Database only
docker compose up -d db

# API
cd api && ./gradlew bootRun

# Dashboard
cd dashboard && npm install && npm run dev
```

### Project structure

```
jobhunter/
├── api/           # Spring Boot 3 backend (Java 21)
├── dashboard/     # React 18 + Vite + Tailwind dashboard
├── mcp-server/    # TypeScript MCP server
├── scripts/       # Start scripts (start-api.sh, start-dashboard.sh, dev.sh)
├── profile.yaml   # Your profile, filters, and scoring config
├── keywords.yaml  # Keyword extraction patterns for MCP
└── docker-compose.yml
```

---

## FAQ

**My jobs aren't being filtered correctly.**
Check that your `include-patterns` cover the role titles you want. Add a `\\b` word boundary around short terms (`\\bjava\\b`) to avoid false matches. Restart the API after editing.

**A job requiring 8+ years appeared in my results.**
The YOE filter runs when descriptions are available. Jobs ingested without a description get descriptions backfilled on the next enrichment cycle, after which the filter re-runs. You can also trigger `POST /api/admin/refilter-language` to re-evaluate all jobs.

**berlinstartupjobs shows "AI provider not available".**
This source uses AI to parse the page. Set `JOBHUNTER_AI_API_KEY` in your environment. If using Docker, the variable must be set before starting the container (or added to a `.env` file in the project root).

**LinkedIn shows EMPTY.**
LinkedIn requires the MCP server to be running and authenticated. Check `make status` and the LinkedIn Setup section above.

**How do I add a company?**
Use the dashboard "Companies" page, the `add_company` MCP tool, or the REST API directly. JobHunter auto-detects the ATS from the careers URL.
