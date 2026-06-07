package dev.jobhunter.repository;

import dev.jobhunter.model.AggregatorRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AggregatorRunRepository extends JpaRepository<AggregatorRun, UUID> {

    Optional<AggregatorRun> findBySourceName(String sourceName);
}
