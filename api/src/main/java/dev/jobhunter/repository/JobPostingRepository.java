package dev.jobhunter.repository;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.MatchScore;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company WHERE j.isActive = true AND j.languageFilter = :filter " +
           "AND j.id NOT IN (SELECT ms.job.id FROM MatchScore ms)")
    Page<JobPosting> findUnscoredActiveJobs(@Param("filter") FilterDecision filter, Pageable pageable);

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company WHERE j.isActive = true AND j.languageFilter = :filter " +
           "AND j.endpoint.id = :endpointId AND j.id NOT IN (SELECT ms.job.id FROM MatchScore ms)")
    List<JobPosting> findUnscoredActiveJobsByEndpoint(@Param("endpointId") UUID endpointId,
                                                      @Param("filter") FilterDecision filter);

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company WHERE j.isActive = true AND j.languageFilter = :filter " +
           "AND j.source = :source AND j.id NOT IN (SELECT ms.job.id FROM MatchScore ms)")
    List<JobPosting> findUnscoredActiveJobsBySource(@Param("source") JobSource source,
                                                    @Param("filter") FilterDecision filter);

    Optional<JobPosting> findBySourceAndExternalId(JobSource source, String externalId);

    Optional<JobPosting> findFirstByApplyUrl(String applyUrl);

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
    List<JobPosting> findBySourceAndLanguageFilterAndDescriptionIsNull(@Param("source") JobSource source, @Param("filter") FilterDecision filter);

    @Query("SELECT j FROM JobPosting j WHERE j.source = :source AND j.languageFilter = :filter AND j.postedDate IS NULL AND j.isActive = true")
    List<JobPosting> findBySourceAndLanguageFilterAndPostedDateIsNull(@Param("source") JobSource source, @Param("filter") FilterDecision filter);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilter(FilterDecision filter, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndLocationContainingIgnoreCase(
            FilterDecision filter, String location, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndCompanyName(
            FilterDecision filter, String companyName, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndCompanyNameAndLocationContainingIgnoreCase(
            FilterDecision filter, String companyName, String location, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedTrue(Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndDiscoveredDate(
            FilterDecision filter, java.time.LocalDate discoveredDate, Pageable pageable);

    Optional<JobPosting> findFirstByFingerprintAndLanguageFilter(String fingerprint, FilterDecision filter);

    @Query("SELECT jp FROM JobPosting jp WHERE jp.fingerprint = :fingerprint " +
           "AND jp.isActive = true " +
           "AND jp.source NOT IN :excludedSources")
    Optional<JobPosting> findAtsJobByFingerprint(@Param("fingerprint") String fingerprint,
                                                 @Param("excludedSources") List<JobSource> excludedSources);

    @Query(value = "SELECT id FROM job_posting WHERE CAST(id AS TEXT) LIKE :prefix || '%' LIMIT 1", nativeQuery = true)
    Optional<UUID> findIdByPrefix(@Param("prefix") String prefix);

    @Query("SELECT jp FROM JobPosting jp WHERE jp.company.normalizedName = :companyName " +
           "AND LOWER(jp.title) LIKE LOWER(CONCAT('%', :titleKeyword, '%')) " +
           "AND jp.isActive = true AND jp.source NOT IN :excludedSources")
    List<JobPosting> findByCompanyNormalizedNameAndTitleContaining(
            @Param("companyName") String companyName, @Param("titleKeyword") String titleKeyword,
            @Param("excludedSources") List<JobSource> excludedSources);

    boolean existsBySourceAndExternalId(JobSource source, String externalId);

    @Query("SELECT j.externalId FROM JobPosting j WHERE j.source = :source")
    List<String> findExternalIdsBySource(@Param("source") JobSource source);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSource(
            FilterDecision languageFilter, JobSource source, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceNotIn(
            FilterDecision languageFilter, List<JobSource> source, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceAndCompanyName(
            FilterDecision languageFilter, JobSource source, String companyName, Pageable pageable);

    Page<JobPosting> findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceNotInAndCompanyName(
            FilterDecision languageFilter, List<JobSource> source, String companyName, Pageable pageable);

    List<JobPosting> findByDiscoveredDateBeforeAndAppliedFalse(LocalDate cutoff);

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.applied = false AND j.hidden = false AND j.languageFilter = :filter " +
           "AND j.source NOT IN :excludedSources " +
           "AND (LOWER(j.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(j.company.name) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "AND (:company IS NULL OR j.company.name = :company)")
    Page<JobPosting> searchByQuery(@Param("filter") FilterDecision filter,
                                   @Param("excludedSources") List<JobSource> excludedSources,
                                   @Param("query") String query,
                                   @Param("company") String company,
                                   Pageable pageable);

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.applied = false AND j.hidden = false AND j.languageFilter = :filter " +
           "AND j.source = :source " +
           "AND (LOWER(j.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(j.company.name) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "AND (:company IS NULL OR j.company.name = :company)")
    Page<JobPosting> searchByQueryAndSource(@Param("filter") FilterDecision filter,
                                            @Param("source") JobSource source,
                                            @Param("query") String query,
                                            @Param("company") String company,
                                            Pageable pageable);

    @Query("SELECT j FROM JobPosting j WHERE j.source IN :sources AND j.source != 'LINKEDIN' " +
           "AND j.applyUrl IS NOT NULL AND j.isActive = true AND j.languageFilter = 'KEEP' " +
           "AND (j.description IS NULL OR LENGTH(j.description) < :minLength)")
    List<JobPosting> findAggregatorJobsNeedingDescription(@Param("sources") List<JobSource> sources,
                                                          @Param("minLength") int minLength);

    List<JobPosting> findByPosterContactId(UUID posterContactId);

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.languageFilter = 'KEEP' AND j.description IS NOT NULL AND j.description <> ''")
    List<JobPosting> findActiveKeptJobsWithDescription();

    @Query("SELECT j FROM JobPosting j LEFT JOIN j.matchScore ms LEFT JOIN j.opportunityScore os " +
           "WHERE j.isActive = true AND j.applied = false AND j.hidden = false " +
           "AND j.languageFilter = :filter AND COALESCE(j.postedDate, j.discoveredDate) >= :since")
    Page<JobPosting> findRecentlyPostedJobs(@Param("filter") FilterDecision filter,
                                           @Param("since") LocalDate since,
                                           Pageable pageable);

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.visaSponsorship = 'PENDING' AND j.discoveredDate < :cutoff")
    List<JobPosting> findActivePendingVisaJobsDiscoveredBefore(@Param("cutoff") LocalDate cutoff);
}
