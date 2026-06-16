package dev.jobhunter.people.repository;

import dev.jobhunter.people.model.ContactDiscoveryRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactDiscoveryRunRepository extends JpaRepository<ContactDiscoveryRun, UUID> {

    List<ContactDiscoveryRun> findByCompanyIdOrderByRunAtDesc(UUID companyId);
}
