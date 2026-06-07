package dev.jobhunter.repository;

import dev.jobhunter.model.DiscoveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DiscoveryEventRepository extends JpaRepository<DiscoveryEvent, UUID> {

    List<DiscoveryEvent> findByProvider(String provider);
}
