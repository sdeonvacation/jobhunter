package dev.jobhunter.repository;

import dev.jobhunter.model.Application;
import dev.jobhunter.model.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Optional<Application> findByJobId(UUID jobId);

    List<Application> findByStatus(ApplicationStatus status);
}
