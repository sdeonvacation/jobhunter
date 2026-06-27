package dev.jobhunter.repository;

import dev.jobhunter.model.JobEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobEvaluationRepository extends JpaRepository<JobEvaluation, UUID> {

    Optional<JobEvaluation> findByJobId(UUID jobId);

    @Transactional
    void deleteByJobId(UUID jobId);

    boolean existsByJobId(UUID jobId);
}
