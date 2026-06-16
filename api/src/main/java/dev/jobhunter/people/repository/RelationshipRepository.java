package dev.jobhunter.people.repository;

import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {

    Optional<Relationship> findByContactId(UUID contactId);

    Page<Relationship> findByStatus(RelationshipStatus status, Pageable pageable);

    @Query("SELECT r FROM Relationship r WHERE r.status = 'CONTACTED' AND r.lastContactAt < :threshold")
    List<Relationship> findGhostCandidates(@Param("threshold") LocalDateTime threshold);

    @Query("SELECT COUNT(r) FROM Relationship r WHERE r.status = :status")
    long countByStatus(@Param("status") RelationshipStatus status);

    List<Relationship> findByContactCompanyId(UUID companyId);
}
