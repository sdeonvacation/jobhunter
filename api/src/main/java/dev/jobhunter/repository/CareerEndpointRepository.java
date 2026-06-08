package dev.jobhunter.repository;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CareerEndpointRepository extends JpaRepository<CareerEndpoint, UUID> {

    List<CareerEndpoint> findByCompanyId(UUID companyId);

    List<CareerEndpoint> findByIsActiveTrueAndAtsType(AtsType atsType);

    @Query("SELECT e FROM CareerEndpoint e JOIN FETCH e.company WHERE e.isActive = true " +
            "AND e.atsType != 'CUSTOM' " +
            "AND (e.lastCrawledAt IS NULL OR e.lastCrawledAt < :cutoff) " +
            "ORDER BY e.lastCrawledAt ASC NULLS FIRST")
    List<CareerEndpoint> findEndpointsDueForCrawl(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Query("SELECT e FROM CareerEndpoint e JOIN FETCH e.company WHERE e.isActive = true " +
            "AND e.atsType != 'CUSTOM' " +
            "AND (e.lastCrawledAt IS NULL OR e.lastCrawledAt < :cutoff) " +
            "ORDER BY e.lastCrawledAt ASC NULLS FIRST")
    List<CareerEndpoint> findAllDueForCrawl(@Param("cutoff") LocalDateTime cutoff);

    default List<CareerEndpoint> findDueForCrawl(LocalDateTime cutoff, int limit) {
        return findEndpointsDueForCrawl(cutoff, PageRequest.of(0, limit));
    }

    @Query("SELECT e FROM CareerEndpoint e JOIN FETCH e.company WHERE e.isActive = true " +
            "AND e.atsType = 'CUSTOM' " +
            "AND (e.lastCrawledAt IS NULL OR e.lastCrawledAt < :cutoff) " +
            "ORDER BY e.lastCrawledAt ASC NULLS FIRST")
    List<CareerEndpoint> findCustomEndpointsDueForCrawl(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Query("SELECT e FROM CareerEndpoint e JOIN FETCH e.company WHERE e.isActive = true AND e.lastCrawlStatus = :status")
    List<CareerEndpoint> findByIsActiveTrueAndLastCrawlStatus(@Param("status") dev.jobhunter.model.enums.CrawlStatus status);

    @Query("SELECT e FROM CareerEndpoint e JOIN FETCH e.company WHERE e.isActive = true AND e.atsType != 'CUSTOM' ORDER BY e.lastCrawledAt ASC NULLS FIRST")
    List<CareerEndpoint> findAllActiveNonCustom();

    long countByIsActiveTrue();

    long countByIsActiveTrueAndLastCrawlStatus(dev.jobhunter.model.enums.CrawlStatus status);

    long countByIsActiveTrueAndLastCrawlStatusIsNull();
}
