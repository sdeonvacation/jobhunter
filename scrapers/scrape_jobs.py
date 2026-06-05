#!/usr/bin/env python3
"""
JobHub external scrapers — Arbeitnow + Indeed/LinkedIn via JobSpy.
Runs as a standalone script, inserts jobs directly into PostgreSQL.
"""

import json
import logging
import os
import re
import uuid
from datetime import datetime, date
from typing import Optional

import psycopg2
import requests
import yaml
from langdetect import detect_langs, LangDetectException

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

DB_CONFIG = {
    "host": "localhost",
    "port": 5435,
    "dbname": "jobhub",
    "user": "jobhub",
    "password": "jobhub",
}

# --- Filters (loaded from profile.yaml) ---

PROFILE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "profile.yaml"
)


def _load_profile():
    with open(PROFILE_PATH, "r") as f:
        return yaml.safe_load(f)


_profile = _load_profile()
_filters = _profile.get("filters", {})
_role_cfg = _filters.get("role", {})
_location_cfg = _filters.get("location", {})

# Build include pattern from profile
_include_patterns = _role_cfg.get("include-patterns", [])
ENGINEERING_PATTERN = re.compile(
    "(" + "|".join(_include_patterns) + ")",
    re.IGNORECASE,
)

# Build exclude pattern from profile
_exclude_keywords = _role_cfg.get("exclude-keywords", [])
EXCLUDED_ROLES = re.compile(
    "(" + "|".join(r"\b" + kw + r"\b" for kw in _exclude_keywords) + ")",
    re.IGNORECASE,
)

# Build location patterns from profile
_germany_cities = _location_cfg.get("germany-cities", [])
GERMANY_PATTERN = re.compile(
    "(" + "|".join(_germany_cities) + ")",
    re.IGNORECASE,
)

_remote_patterns = _location_cfg.get("remote-patterns", [])
REMOTE_PATTERN = re.compile(
    "(" + "|".join(_remote_patterns) + ")",
    re.IGNORECASE,
)


def is_target_location(location: str) -> bool:
    if not location:
        return False
    if GERMANY_PATTERN.search(location):
        return True
    if REMOTE_PATTERN.search(location.strip()):
        return True
    return False


GERMAN_CONFIDENCE_THRESHOLD = 0.7
MIN_TEXT_LENGTH_FOR_DETECTION = 50


def is_german_language(title: str, description: str) -> bool:
    """Detect German-language postings using statistical language detection."""
    text = ((title or "") + " " + (description or "")).strip()
    if len(text) < MIN_TEXT_LENGTH_FOR_DETECTION:
        return False
    try:
        langs = detect_langs(text[:1000])
        for lang in langs:
            if lang.lang == "de" and lang.prob >= GERMAN_CONFIDENCE_THRESHOLD:
                return True
    except LangDetectException:
        pass
    return False


def is_relevant_title(title: str) -> bool:
    if not title:
        return False
    if EXCLUDED_ROLES.search(title):
        return False
    return bool(ENGINEERING_PATTERN.search(title))


def load_companies_with_endpoints(cur) -> set:
    """Load normalized names of companies that have active career endpoints."""
    cur.execute(
        "SELECT c.normalized_name FROM company c "
        "JOIN career_endpoint ce ON ce.company_id = c.id "
        "WHERE ce.is_active = true"
    )
    return {row[0] for row in cur.fetchall()}


def get_or_create_company(cur, company_name: str) -> str:
    """Get existing company or create a new one. Returns company UUID."""
    if not company_name:
        company_name = "Unknown"

    normalized = re.sub(r"[^a-z0-9]", "", company_name.lower())[:255]

    cur.execute("SELECT id FROM company WHERE normalized_name = %s", (normalized,))
    row = cur.fetchone()
    if row:
        return str(row[0])

    company_id = str(uuid.uuid4())
    cur.execute(
        """
        INSERT INTO company (id, name, normalized_name, domain, status, discovered_via, priority_score, created_at, updated_at)
        VALUES (%s, %s, %s, NULL, 'DISCOVERED', 'SCRAPER', 50, now(), now())
        ON CONFLICT (normalized_name) DO NOTHING
    """,
        (company_id, company_name[:255], normalized),
    )

    if cur.rowcount == 0:
        # Race condition — fetch again
        cur.execute("SELECT id FROM company WHERE normalized_name = %s", (normalized,))
        row = cur.fetchone()
        return str(row[0]) if row else company_id

    return company_id


def insert_job(
    cur, job: dict, source: str, companies_with_endpoints: Optional[set] = None
) -> bool:
    """Insert a job if it doesn't already exist. Returns True if inserted."""
    external_id = job.get("external_id")
    if not external_id:
        return False

    # Skip jobs from companies that already have an active career endpoint
    # (those companies are crawled directly with better apply URLs)
    company_name = job.get("company_name", "")
    if companies_with_endpoints and company_name:
        normalized = re.sub(r"[^a-z0-9]", "", company_name.lower())[:255]
        if normalized in companies_with_endpoints:
            return False

    # Check if exists
    cur.execute(
        "SELECT 1 FROM job_posting WHERE external_id = %s AND source = %s",
        (external_id, source),
    )
    if cur.fetchone():
        return False

    # Determine filter status
    title = job.get("title", "")
    location = job.get("location", "")
    description = job.get("description", "")

    if is_german_language(title, description):
        language_filter = "SKIP"
        filter_reason = "German JD"
    elif not is_relevant_title(title):
        language_filter = "SKIP"
        filter_reason = "non-engineering role"
    elif not is_target_location(location):
        language_filter = "SKIP"
        filter_reason = "location: non-target"
    else:
        language_filter = "KEEP"
        filter_reason = None

    company_id = get_or_create_company(cur, job.get("company_name") or "Unknown")

    cur.execute(
        """
        INSERT INTO job_posting (id, company_id, external_id, title, location, description, apply_url, 
                                 source, is_active, language_filter, filter_reason, 
                                 posted_date, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, true, %s, %s, %s, now(), now())
        ON CONFLICT DO NOTHING
    """,
        (
            str(uuid.uuid4()),
            company_id,
            external_id,
            title[:500] if title else None,
            location[:500] if location else None,
            job.get("description", "")[:10000],
            job.get("apply_url"),
            source,
            language_filter,
            filter_reason,
            job.get("posted_date"),
        ),
    )
    return cur.rowcount > 0


# --- Arbeitnow ---


def scrape_arbeitnow(max_pages: int = 5) -> list:
    """Fetch jobs from Arbeitnow free API."""
    all_jobs = []
    for page in range(1, max_pages + 1):
        try:
            resp = requests.get(
                f"https://www.arbeitnow.com/api/job-board-api?page={page}", timeout=15
            )
            resp.raise_for_status()
            data = resp.json()
            jobs = data.get("data", [])
            if not jobs:
                break
            for j in jobs:
                all_jobs.append(
                    {
                        "external_id": j.get("slug") or j.get("url", "").split("/")[-1],
                        "company_name": j.get("company_name", "Unknown"),
                        "title": j.get("title", ""),
                        "location": j.get("location", ""),
                        "description": j.get("description", ""),
                        "apply_url": j.get("url"),
                        "posted_date": parse_date(j.get("created_at")),
                    }
                )
            log.info(f"Arbeitnow page {page}: {len(jobs)} jobs")
        except Exception as e:
            log.error(f"Arbeitnow page {page} failed: {e}")
            break
    return all_jobs


# --- JobSpy (Indeed/LinkedIn) ---


def scrape_jobspy(
    search_terms: list, location: str = "Germany", results_wanted: int = 50
) -> list:
    """Fetch jobs from Indeed/LinkedIn via python-jobspy."""
    try:
        from jobspy import scrape_jobs
    except ImportError:
        log.error("python-jobspy not installed")
        return []

    all_jobs = []
    for term in search_terms:
        try:
            log.info(f'JobSpy searching: "{term}" in {location}')
            df = scrape_jobs(
                site_name=["indeed"],  # LinkedIn rate-limits too aggressively
                search_term=term,
                location=location,
                results_wanted=results_wanted,
                country_indeed="Germany",
                hours_old=168,  # last 7 days
            )
            if df is not None and not df.empty:
                for _, row in df.iterrows():
                    all_jobs.append(
                        {
                            "external_id": f"indeed_{row.get('id', str(uuid.uuid4())[:8])}",
                            "company_name": str(row.get("company", "Unknown")),
                            "title": str(row.get("title", "")),
                            "location": str(row.get("location", "")),
                            "description": str(row.get("description", ""))[:10000],
                            "apply_url": str(row.get("job_url", "")),
                            "posted_date": parse_jobspy_date(row.get("date_posted")),
                        }
                    )
                log.info(f'JobSpy "{term}": {len(df)} results')
            else:
                log.info(f'JobSpy "{term}": 0 results')
        except Exception as e:
            log.error(f'JobSpy "{term}" failed: {e}')
    return all_jobs


def parse_date(val) -> Optional[date]:
    if not val:
        return None
    try:
        if isinstance(val, int):
            return datetime.fromtimestamp(val).date()
        return datetime.fromisoformat(str(val).replace("Z", "+00:00")).date()
    except Exception:
        return None


def parse_jobspy_date(val) -> Optional[date]:
    if val is None or str(val) == "NaT":
        return None
    try:
        if hasattr(val, "date"):
            return val.date()
        return datetime.fromisoformat(str(val)).date()
    except Exception:
        return None


def main():
    log.info("=== JobHub External Scrapers ===")

    # Connect to DB
    conn = psycopg2.connect(**DB_CONFIG)
    conn.autocommit = False
    cur = conn.cursor()

    # Load companies already tracked via career endpoints (skip their Indeed duplicates)
    companies_with_endpoints = load_companies_with_endpoints(cur)
    log.info(
        f"Loaded {len(companies_with_endpoints)} companies with active endpoints (will skip)"
    )

    total_inserted = 0

    # 1. Arbeitnow
    log.info("--- Arbeitnow ---")
    arbeitnow_jobs = scrape_arbeitnow(max_pages=10)
    inserted = 0
    for job in arbeitnow_jobs:
        if insert_job(cur, job, "ARBEITNOW", companies_with_endpoints):
            inserted += 1
    conn.commit()
    log.info(f"Arbeitnow: {len(arbeitnow_jobs)} fetched, {inserted} new inserted")
    total_inserted += inserted

    # 2. JobSpy (Indeed)
    log.info("--- JobSpy (Indeed) ---")
    search_terms = [
        "backend engineer",
        "software engineer",
        "devops engineer",
        "platform engineer",
        "java developer",
        "python developer",
        "cloud engineer",
        "SRE site reliability",
    ]
    jobspy_jobs = scrape_jobspy(search_terms, location="Germany", results_wanted=30)
    inserted = 0
    for job in jobspy_jobs:
        if insert_job(cur, job, "INDEED", companies_with_endpoints):
            inserted += 1
    conn.commit()
    log.info(f"JobSpy: {len(jobspy_jobs)} fetched, {inserted} new inserted")
    total_inserted += inserted

    cur.close()
    conn.close()
    log.info(f"=== Done. Total new jobs: {total_inserted} ===")

    # Trigger scoring for newly inserted jobs
    if total_inserted > 0:
        try:
            resp = requests.post("http://localhost:8080/api/admin/score", timeout=60)
            log.info(f"Scoring triggered: {resp.status_code} {resp.text}")
        except Exception as e:
            log.warning(f"Could not trigger scoring (API down?): {e}")


if __name__ == "__main__":
    main()
