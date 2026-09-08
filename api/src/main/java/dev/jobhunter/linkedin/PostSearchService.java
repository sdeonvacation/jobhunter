package dev.jobhunter.linkedin;

import java.util.List;

public interface PostSearchService {

    /**
     * Search LinkedIn posts for candidates referencing the job.
     * slim=true → single best query variant.
     * Returns deduped candidates; empty on failure/budget exhaustion.
     */
    List<SignalScorer.CandidatePost> searchCandidates(JobContextResolver.JobContext ctx, CallBudget budget, boolean slim, String recencyWindow);
}
