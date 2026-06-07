package dev.jobhunter.service;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.MatchScore;
import dev.jobhunter.model.OpportunityScore;
import dev.jobhunter.repository.MatchScoreRepository;
import dev.jobhunter.repository.OpportunityScoreRepository;
import dev.jobhunter.scoring.OpportunityScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes and persists OpportunityScore for jobs.
 */
@Slf4j
@Service
public class OpportunityScoringService {

    private final OpportunityScorer opportunityScorer;
    private final OpportunityScoreRepository opportunityScoreRepository;
    private final MatchScoreRepository matchScoreRepository;

    public OpportunityScoringService(OpportunityScorer opportunityScorer,
                                     OpportunityScoreRepository opportunityScoreRepository,
                                     MatchScoreRepository matchScoreRepository) {
        this.opportunityScorer = opportunityScorer;
        this.opportunityScoreRepository = opportunityScoreRepository;
        this.matchScoreRepository = matchScoreRepository;
    }

    /**
     * Score a single job opportunity. Skips if already scored.
     */
    @Transactional
    public Optional<OpportunityScore> scoreJob(JobPosting job) {
        Optional<OpportunityScore> existing = opportunityScoreRepository.findByJobId(job.getId());
        if (existing.isPresent()) {
            return existing;
        }

        MatchScore matchScore = matchScoreRepository.findByJobId(job.getId()).orElse(null);
        OpportunityScorer.OpportunityResult result = opportunityScorer.score(job, matchScore);

        OpportunityScore score = OpportunityScore.builder()
                .id(UUID.randomUUID())
                .job(job)
                .score(result.score())
                .breakdown(result.breakdown())
                .computedAt(LocalDateTime.now())
                .build();

        OpportunityScore saved = opportunityScoreRepository.save(score);
        log.info("Opportunity score for job {} ({}): {}",
                job.getId(), job.getTitle(), result.score());
        return Optional.of(saved);
    }

    /**
     * Batch score multiple jobs.
     */
    @Transactional
    public int scoreJobs(List<JobPosting> jobs) {
        int scored = 0;
        for (JobPosting job : jobs) {
            if (scoreJob(job).isPresent()) {
                scored++;
            }
        }
        return scored;
    }
}
