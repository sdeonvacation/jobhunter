package dev.jobhunter.service;

import dev.jobhunter.ingestion.BackfillPostProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Post-backfill step: re-scores all unscored jobs after descriptions are filled.
 */
@Slf4j
@Service
public class ScoringPostProcessor implements BackfillPostProcessor {

    private final ScoringService scoringService;

    public ScoringPostProcessor(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @Override
    public void process() {
        log.info("Post-backfill scoring pass");
        scoringService.scoreAllUnscored();
    }
}
