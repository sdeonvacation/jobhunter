package dev.jobhunter.repository;

import dev.jobhunter.model.JobOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobOutcomeRepository extends JpaRepository<JobOutcome, UUID> {

    List<JobOutcome> findByApplicationId(UUID applicationId);
}
