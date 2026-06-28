package dev.jobhunter.service;

import dev.jobhunter.ingestion.BackfillPostProcessor;
import dev.jobhunter.repository.MatchScoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Post-backfill step: invalidates stale zero-scores and re-scores all unscored jobs.
 */
@Slf4j
@Service
public class ScoringPostProcessor implements BackfillPostProcessor {

    private final ScoringService scoringService;
    private final MatchScoreRepository matchScoreRepository;

    public ScoringPostProcessor(ScoringService scoringService,
                                MatchScoreRepository matchScoreRepository) {
        this.scoringService = scoringService;
        this.matchScoreRepository = matchScoreRepository;
    }

    @Override
    public void process() {
        // Invalidate stale zero-scores: jobs scored with no description that now have one
        int invalidated = matchScoreRepository.deleteStaleZeroScores();
        if (invalidated > 0) {
            log.info("Post-backfill: invalidated {} stale zero-scores", invalidated);
        }
        log.info("Post-backfill scoring pass");
        scoringService.scoreAllUnscored();
    }
}
