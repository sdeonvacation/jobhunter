package dev.jobhub.service;

import dev.jobhub.model.JobPosting;
import dev.jobhub.model.MatchScore;
import dev.jobhub.repository.MatchScoreRepository;
import dev.jobhub.scoring.MatchScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scores jobs against personal profile using keyword matching. No AI needed.
 */
@Slf4j
@Service
public class MatchScoringService {

    private final MatchScorer matchScorer;
    private final MatchScoreRepository matchScoreRepository;

    public MatchScoringService(MatchScorer matchScorer,
                               MatchScoreRepository matchScoreRepository) {
        this.matchScorer = matchScorer;
        this.matchScoreRepository = matchScoreRepository;
    }

    /**
     * Score a single job by keyword-matching description against profile.
     */
    @Transactional
    public Optional<MatchScore> scoreJob(JobPosting job) {
        Optional<MatchScore> existing = matchScoreRepository.findByJobId(job.getId());
        if (existing.isPresent()) {
            return existing;
        }

        MatchScorer.MatchResult result = matchScorer.scoreFromDescription(
                job.getTitle(), job.getDescription());

        MatchScore score = MatchScore.builder()
                .id(UUID.randomUUID())
                .job(job)
                .overallScore(result.overallScore())
                .matchedSkills(result.matchedSkills())
                .missingSkills(result.missingSkills())
                .recommendation(result.recommendation())
                .scoredAt(LocalDateTime.now())
                .build();

        MatchScore saved = matchScoreRepository.save(score);
        log.debug("Scored job {} ({}): score={}, matched={}, recommendation={}",
                job.getId(), job.getTitle(), result.overallScore(),
                result.matchedSkills(), result.recommendation());
        return Optional.of(saved);
    }

    /**
     * Score multiple jobs in batch.
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
