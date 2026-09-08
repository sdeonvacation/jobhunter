package dev.jobhunter.repository;

import dev.jobhunter.linkedin.RecruiterPostCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecruiterPostCheckRepository extends JpaRepository<RecruiterPostCheck, UUID> {

    Optional<RecruiterPostCheck> findByJobUrlAndExpiresAtAfter(String jobUrl, LocalDateTime now);

    Optional<RecruiterPostCheck> findByJobUrl(String jobUrl);

    List<RecruiterPostCheck> findByJobUrlInAndExpiresAtAfter(List<String> jobUrls, LocalDateTime now);

    void deleteByExpiresAtBefore(LocalDateTime now);
}