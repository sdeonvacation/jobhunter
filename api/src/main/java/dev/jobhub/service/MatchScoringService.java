package dev.jobhub.service;

import dev.jobhub.model.JobPosting;
import dev.jobhub.model.JobSkill;
import dev.jobhub.model.MatchScore;
import dev.jobhub.repository.JobSkillRepository;
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
 * Scores jobs against personal profile and persists MatchScore.
 */
@Slf4j
@Service
public class MatchScoringService {

    private final MatchScorer matchScorer;
    private final MatchScoreRepository matchScoreRepository;
    private final JobSkillRepository jobSkillRepository;

    public MatchScoringService(MatchScorer matchScorer,
                               MatchScoreRepository matchScoreRepository,
                               JobSkillRepository jobSkillRepository) {
        this.matchScorer = matchScorer;
        this.matchScoreRepository = matchScoreRepository;
        this.jobSkillRepository = jobSkillRepository;
    }

    /**
     * Score a single job. Skips if already scored.
     */
    @Transactional
    public Optional<MatchScore> scoreJob(JobPosting job) {
        Optional<MatchScore> existing = matchScoreRepository.findByJobId(job.getId());
        if (existing.isPresent()) {
            return existing;
        }

        List<JobSkill> skills = jobSkillRepository.findByJobId(job.getId());
        if (skills.isEmpty()) {
            log.debug("No skills extracted for job {}, skipping scoring", job.getId());
            return Optional.empty();
        }

        MatchScorer.MatchResult result = matchScorer.score(skills);

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
        log.info("Scored job {} ({}): score={}, recommendation={}",
                job.getId(), job.getTitle(), result.overallScore(), result.recommendation());
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
