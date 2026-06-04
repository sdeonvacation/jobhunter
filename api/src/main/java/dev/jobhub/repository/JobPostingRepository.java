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

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company WHERE j.isActive = true AND j.languageFilter = :filter " +
           "AND j.id NOT IN (SELECT ms.job.id FROM MatchScore ms)")
    Page<JobPosting> findUnscoredActiveJobs(@Param("filter") FilterDecision filter, Pageable pageable);

    Optional<JobPosting> findBySourceAndExternalId(AtsType source, String externalId);

    List<JobPosting> findByEndpointIdAndIsActiveTrue(UUID endpointId);

    List<JobPosting> findByCompanyIdAndIsActiveTrue(UUID companyId);

    Page<JobPosting> findByCompanyIdAndIsActiveTrue(UUID companyId, Pageable pageable);

    Page<JobPosting> findByIsActiveTrue(Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndLanguageFilter(FilterDecision filter, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndLanguageFilterAndLocationContainingIgnoreCase(
            FilterDecision filter, String location, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndLanguageFilterAndCompanyName(
            FilterDecision filter, String companyName, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndLanguageFilterAndCompanyNameAndLocationContainingIgnoreCase(
            FilterDecision filter, String companyName, String location, Pageable pageable);

    @Query("SELECT DISTINCT j.company.name FROM JobPosting j WHERE j.isActive = true AND j.languageFilter = :filter ORDER BY j.company.name")
    List<String> findDistinctCompanyNamesWithVisibleJobs(@Param("filter") FilterDecision filter);

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.endpoint WHERE j.source = :source AND j.languageFilter = :filter AND j.description IS NULL AND j.isActive = true")
    List<JobPosting> findBySourceAndLanguageFilterAndDescriptionIsNull(@Param("source") AtsType source, @Param("filter") FilterDecision filter);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndLanguageFilter(FilterDecision filter, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndLocationContainingIgnoreCase(
            FilterDecision filter, String location, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndCompanyName(
            FilterDecision filter, String companyName, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndCompanyNameAndLocationContainingIgnoreCase(
            FilterDecision filter, String companyName, String location, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedTrue(Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndDiscoveredDate(
            FilterDecision filter, java.time.LocalDate discoveredDate, Pageable pageable);

    Optional<JobPosting> findFirstByFingerprintAndLanguageFilter(String fingerprint, FilterDecision filter);
}
