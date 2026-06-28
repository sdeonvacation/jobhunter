package dev.jobhunter.repository;

import dev.jobhunter.model.MatchScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatchScoreRepository extends JpaRepository<MatchScore, UUID> {

    Optional<MatchScore> findByJobId(UUID jobId);

    @Transactional
    void deleteByJobId(UUID jobId);

    /**
     * Delete stale zero-scores for jobs that now have descriptions.
     * These are jobs scored with no description (score=0, no matches) that later got backfilled.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM match_score WHERE overall_score = 0 AND matched_skills = '[]'::jsonb " +
            "AND job_id IN (SELECT id FROM job_posting WHERE is_active = true AND language_filter = 'KEEP' AND description IS NOT NULL)",
            nativeQuery = true)
    int deleteStaleZeroScores();
}
