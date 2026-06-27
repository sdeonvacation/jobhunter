package dev.jobhunter.repository;

import dev.jobhunter.model.CoverLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CoverLetterRepository extends JpaRepository<CoverLetter, UUID> {

    List<CoverLetter> findByJobIdOrderByVersionDesc(UUID jobId);

    Optional<CoverLetter> findFirstByJobIdOrderByVersionDesc(UUID jobId);

    int countByJobId(UUID jobId);
}
