package dev.jobhub.repository;

import dev.jobhub.model.OpportunityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpportunityScoreRepository extends JpaRepository<OpportunityScore, UUID> {

    Optional<OpportunityScore> findByJobId(UUID jobId);
}
