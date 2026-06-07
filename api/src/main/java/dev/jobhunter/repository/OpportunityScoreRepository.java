package dev.jobhunter.repository;

import dev.jobhunter.model.OpportunityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpportunityScoreRepository extends JpaRepository<OpportunityScore, UUID> {

    Optional<OpportunityScore> findByJobId(UUID jobId);
}
