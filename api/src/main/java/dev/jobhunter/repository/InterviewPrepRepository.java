package dev.jobhunter.repository;

import dev.jobhunter.model.InterviewPrep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewPrepRepository extends JpaRepository<InterviewPrep, UUID> {

    Optional<InterviewPrep> findByJobId(UUID jobId);
}
