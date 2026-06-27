package dev.jobhunter.repository;

import dev.jobhunter.linkedin.OutreachContact;
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
public interface OutreachContactRepository extends JpaRepository<OutreachContact, UUID> {

    List<OutreachContact> findByCompanyId(UUID companyId);

    Optional<OutreachContact> findByLinkedinUrl(String linkedinUrl);

    @Query("SELECT COUNT(c) FROM OutreachContact c WHERE c.connectionSentAt >= :startOfDay")
    long countConnectionsSentToday(@Param("startOfDay") LocalDateTime startOfDay);

    @Query("SELECT c FROM OutreachContact c WHERE c.company.id = :companyId ORDER BY c.contactPriorityScore DESC")
    List<OutreachContact> findByCompanyIdOrderByPriorityDesc(@Param("companyId") UUID companyId);

    @Query("SELECT c FROM OutreachContact c ORDER BY c.contactPriorityScore DESC")
    Page<OutreachContact> findAllOrderByPriorityDesc(Pageable pageable);

    @Query("SELECT c FROM OutreachContact c WHERE c.seniority = :seniority ORDER BY c.contactPriorityScore DESC")
    List<OutreachContact> findBySeniorityOrderByPriorityDesc(@Param("seniority") dev.jobhunter.people.model.enums.Seniority seniority);

    @Query("SELECT AVG(c.contactPriorityScore) FROM OutreachContact c WHERE c.contactPriorityScore > 0")
    Double findAveragePriorityScore();

    @Query("SELECT COUNT(c) FROM OutreachContact c WHERE c.createdAt >= :since")
    long countDiscoveredSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT DISTINCT job_id FROM job_contact", nativeQuery = true)
    List<UUID> findJobIdsWithContacts();

    @Query(value = "SELECT contact_id FROM job_contact WHERE job_id = :jobId", nativeQuery = true)
    List<UUID> findContactIdsByJobId(@Param("jobId") UUID jobId);

    @Query(value = "INSERT INTO job_contact (job_id, contact_id) VALUES (:jobId, :contactId) ON CONFLICT DO NOTHING", nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    void linkContactToJob(@Param("jobId") UUID jobId, @Param("contactId") UUID contactId);
}
