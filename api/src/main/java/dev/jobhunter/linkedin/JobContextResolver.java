package dev.jobhunter.linkedin;

import dev.jobhunter.model.enums.AtsType;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the context of a job posting URL (title, company, location, ...)
 * using the cheapest available source: DB lookup, then LinkedIn MCP, then ATS page scraping.
 */
public interface JobContextResolver {

    Optional<JobContext> resolve(String jobUrl, CallBudget budget);

    record JobContext(
            String title,
            String company,
            String location,
            LocalDate postedDate,
            String applyUrl,
            AtsType atsType,
            String linkedinJobId,   // nullable — set only for LinkedIn URLs
            UUID jobPostingId       // nullable — set when resolved from DB
    ) {}
}