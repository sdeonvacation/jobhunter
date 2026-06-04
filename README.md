# JobHunter

![JobHunter Dashboard](docs/screenshot.png)

**Autonomous job discovery platform that finds, filters, and scores open jobs on various platforms — so you spend time applying, not searching.**

## What JobHub Does

### Discovers jobs automatically
Monitors 500+ company career pages across 12 different hiring platforms. New jobs are picked up within hours of being posted — no manual checking required.

### Filters intelligently
Only shows you what matters (examples):
- Engineering roles only (excludes management, design, legal, HR, etc.)
- Seleted location-based or remote positions
- English-language postings
- Appropriate experience level (skips "10+ years required")
- Deduplicates cross-posted listings

### Scores every job against your profile
Each job gets a match score (0-100) based on how well it fits your skills, weighted by what matters most to you. Jobs are ranked so the best opportunities float to the top.

A "primary language" gate ensures Python/Go/Ruby jobs don't get inflated scores just because they use the same infrastructure tools you know.

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
| Career pages monitored | 500+ across 12 ATS platforms |
| Crawl frequency | Every 6-12 hours |
| Scoring | Keyword match + opportunity composite |
| Filters | Role, location, language, experience, dedup |
| AI features | Cover letter generation, resume tailoring |
| Dashboard | Dark-themed web app with search, filters, applied tracking |
| MCP integration | Query jobs from any AI assistant (Claude, etc.) |

## Supported Hiring Platforms

Greenhouse, Lever, Ashby, Workday, SmartRecruiters, Workable, Personio, Recruitee, JOIN, BambooHR, Breezy, SAP SuccessFactors, Arbeitnow, Indeed (via scraping).

## How It Works (User Perspective)

1. **Configure once** — Define your skills, preferred locations, and what roles to include/exclude in a simple config file.
2. **It runs continuously** — Jobs are crawled, filtered, scored, and ready for you.
3. **Check the dashboard** — Daily Digest shows new opportunities sorted by fit. One click opens the application page.
4. **Track progress** — Mark jobs as applied. See your pipeline at a glance.
5. **Stay informed** — Health page shows which company feeds are working, which are down.

## What Makes It Different

- **Not LinkedIn** — LinkedIn shows you what its algorithm wants you to see, buries relevant roles in spam, and locks insights behind Premium. JobHunter pulls directly from company career pages, scores transparently against YOUR skills, and surfaces everything — no paywall, no "promoted" listings, no recruiter noise.
- **Not another job board** — It pulls directly from company career pages, not aggregators. You see jobs the moment they're posted.
- **Fully configurable** — Every filter, weight, and threshold is tunable. Add a new city, change skill weights, adjust what "senior" means to you.
- **No account required** — Runs locally. Your profile, preferences, and application history stay on your machine.
- **Extensible** — Adding a new company takes one database entry. Adding a new hiring platform takes one extractor class.

## Current Stats

- ~1,100 companies tracked
- ~500 active career endpoints
- ~300+ jobs visible after filtering
- 12 ATS platform extractors
- Scoring across 25+ weighted skills

## Getting Started

### Prerequisites

- Java 21 (Temurin)
- Node.js 18+
- Docker (for PostgreSQL)

### 1. Start the database

```bash
docker compose up -d db
```

This starts PostgreSQL 16 on port 5435.

### 2. Configure your profile

Edit `profile.yaml` at the project root. This single file controls everything about your job search:

- **Who you are** — name, title, years of experience
- **Your skills** — each with proficiency level and weight for scoring
- **Preferences** — locations, salary, seniority, languages
- **Filters** — which roles to include/exclude, which cities to allow, max YOE
- **Scoring** — skill weights, matching variants, thresholds for APPLY/MAYBE recommendations, primary language gate

Example snippet:
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
    exclude-keywords: ["manager", "designer", "devops", "frontend"]
  location:
    germany-cities: ["berlin", "munich", "hamburg", "remote"]
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

No code changes needed — just edit this file and restart the API.

### 3. Start the API

```bash
cd api
./gradlew bootRun
```

API starts on http://localhost:8080. On first run, Liquibase creates all database tables automatically.

### 4. Start the dashboard

```bash
cd dashboard
npm install
npm run dev
```

Dashboard opens at http://localhost:3000.

### 5. Add companies and trigger a crawl

Add companies and their career page URLs to the database, then trigger the first crawl:

```bash
curl -X POST http://localhost:8080/api/admin/crawl
```

### 6. Score the results

```bash
curl -X POST http://localhost:8080/api/admin/score
```

Open the dashboard — your Daily Digest is ready.
