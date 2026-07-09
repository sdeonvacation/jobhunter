package dev.jobhunter.ingestion;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.service.MatchScoringService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Contract for backfilling descriptions on jobs ingested without one.
 * Subclasses implement doBackfill() and return the list of jobs whose descriptions
 * were updated. This base class then rescores all of them — rescoring is mandatory
 * and cannot be skipped or forgotten by individual implementations.
 */
@Slf4j
public abstract class DescriptionBackfiller {

    private final MatchScoringService matchScoringService;

    protected DescriptionBackfiller(MatchScoringService matchScoringService) {
        this.matchScoringService = matchScoringService;
    }

    /**
     * Template method. Calls doBackfill() then rescores every updated job.
     * Called by CrawlService after each crawl cycle.
     */
    public final void backfill() {
        List<JobPosting> updated = doBackfill();
        if (!updated.isEmpty()) {
            log.debug("Rescoring {} jobs after backfill ({})", updated.size(), getClass().getSimpleName());
            updated.forEach(matchScoringService::rescoreJob);
        }
    }

    /**
     * Fetch and persist descriptions for eligible jobs.
     * Must NOT perform any scoring or score deletion — return the updated jobs instead.
     * @return list of JobPosting objects whose descriptions were successfully filled
     */
    protected abstract List<JobPosting> doBackfill();
}
