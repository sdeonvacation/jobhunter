package dev.jobhunter.linkedin;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates recruiter-post detection for a job URL: cache lookup, context
 * resolution, candidate search, signal scoring, AI tie-breaking, contact
 * creation and result persistence.
 */
public interface RecruiterPostDetectionService {

    RecruiterPostCheckResult checkRecruiterPost(String jobUrl, boolean force);

    /** Cache-only read for digest cards. Never triggers LinkedIn calls. Absent/expired URLs omitted. */
    List<RecruiterPostCheckResult> readCached(List<String> jobUrls);

    record RecruiterPostCheckResult(
            String jobUrl,
            RecruiterPostVerdict verdict,
            double confidence,
            List<MatchedPost> matchedPosts,
            UUID contactId,          // nullable — set when HIGH/MEDIUM persisted
            int callsUsed
    ) {}

    record MatchedPost(
            String postUrl, String authorName, String authorTitle,
            String authorLinkedinUrl, String snippet, String postedAt
    ) {}
}