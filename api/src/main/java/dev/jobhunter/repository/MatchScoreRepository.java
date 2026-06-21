package dev.jobhunter.repository;

import dev.jobhunter.model.MatchScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatchScoreRepository extends JpaRepository<MatchScore, UUID> {

    Optional<MatchScore> findByJobId(UUID jobId);

    void deleteByJobId(UUID jobId);
}
