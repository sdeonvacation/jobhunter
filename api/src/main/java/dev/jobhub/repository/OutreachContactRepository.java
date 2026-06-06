package dev.jobhub.repository;

import dev.jobhub.linkedin.OutreachContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutreachContactRepository extends JpaRepository<OutreachContact, UUID> {

    List<OutreachContact> findByCompanyId(UUID companyId);

    Optional<OutreachContact> findByLinkedinUrl(String linkedinUrl);

    @Query("SELECT COUNT(c) FROM OutreachContact c WHERE c.connectionSentAt >= :startOfDay")
    long countConnectionsSentToday(@Param("startOfDay") LocalDateTime startOfDay);
}
