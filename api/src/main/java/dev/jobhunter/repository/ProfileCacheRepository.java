package dev.jobhunter.repository;

import dev.jobhunter.linkedin.ProfileCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfileCacheRepository extends JpaRepository<ProfileCache, UUID> {

    Optional<ProfileCache> findByLinkedinUrlAndExpiresAtAfter(String linkedinUrl, LocalDateTime now);

    void deleteByLinkedinUrl(String linkedinUrl);

    void deleteByExpiresAtBefore(LocalDateTime now);
}
