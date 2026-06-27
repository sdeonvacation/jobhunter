package dev.jobhunter.repository;

import dev.jobhunter.model.FollowUp;
import dev.jobhunter.model.enums.FollowUpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FollowUpRepository extends JpaRepository<FollowUp, UUID> {

    List<FollowUp> findByStatusOrderByScheduledDateAsc(FollowUpStatus status, Pageable pageable);

    List<FollowUp> findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
            FollowUpStatus status, LocalDate date);

    List<FollowUp> findByJobIdOrderByCountAsc(UUID jobId);

    List<FollowUp> findAllByOrderByScheduledDateAsc(Pageable pageable);
}
