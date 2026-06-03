package dev.jobhub.repository;

import dev.jobhub.model.JobPosting;
import dev.jobhub.model.MatchScore;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.FilterDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.languageFilter = :filter " +
           "AND j.id NOT IN (SELECT ms.job.id FROM MatchScore ms)")
    Page<JobPosting> findUnscoredActiveJobs(@Param("filter") FilterDecision filter, Pageable pageable);

    Optional<JobPosting> findBySourceAndExternalId(AtsType source, String externalId);

    List<JobPosting> findByEndpointIdAndIsActiveTrue(UUID endpointId);

    List<JobPosting> findByCompanyIdAndIsActiveTrue(UUID companyId);

    Page<JobPosting> findByCompanyIdAndIsActiveTrue(UUID companyId, Pageable pageable);

    Page<JobPosting> findByIsActiveTrue(Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndLanguageFilter(FilterDecision filter, Pageable pageable);
}
