package dev.jobhunter.repository;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.MatchScore;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company LEFT JOIN MatchScore ms ON ms.job.id = j.id " +
           "WHERE j.isActive = true AND j.languageFilter = :filter AND ms.id IS NULL")
    Page<JobPosting> findUnscoredActiveJobs(@Param("filter") FilterDecision filter, Pageable pageable);

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company LEFT JOIN MatchScore ms ON ms.job.id = j.id " +
           "WHERE j.isActive = true AND j.languageFilter = :filter AND j.endpoint.id = :endpointId AND ms.id IS NULL")
    List<JobPosting> findUnscoredActiveJobsByEndpoint(@Param("endpointId") UUID endpointId,
                                                      @Param("filter") FilterDecision filter);

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.company LEFT JOIN MatchScore ms ON ms.job.id = j.id " +
           "WHERE j.isActive = true AND j.languageFilter = :filter AND j.source = :source AND ms.id IS NULL")
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

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.languageFilter = :filter AND j.description IS NULL AND j.applyUrl IS NOT NULL ORDER BY j.createdAt DESC")
    List<JobPosting> findActiveKeepJobsWithApplyUrlButNoDescription(@Param("filter") FilterDecision filter, Pageable pageable);

    @Query("SELECT j FROM JobPosting j LEFT JOIN FETCH j.endpoint WHERE j.source = :source AND j.languageFilter = :filter AND j.isActive = true AND (j.description IS NULL OR length(j.description) < :maxLen)")
    List<JobPosting> findBySourceAndLanguageFilterAndShortDescription(@Param("source") JobSource source, @Param("filter") FilterDecision filter, @Param("maxLen") int maxLen);

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

    @Query("SELECT j FROM JobPosting j WHERE j.fingerprint = :fingerprint AND j.languageFilter = :filter AND j.source NOT IN :excludedSources")
    Optional<JobPosting> findFirstByFingerprintAndLanguageFilterExcludingSources(
            @Param("fingerprint") String fingerprint,
            @Param("filter") FilterDecision filter,
            @Param("excludedSources") List<JobSource> excludedSources);

    @Query("SELECT j FROM JobPosting j WHERE j.fingerprint = :fingerprint AND j.languageFilter = :filter AND j.source IN :sources")
    List<JobPosting> findByFingerprintAndLanguageFilterAndSourceIn(
            @Param("fingerprint") String fingerprint,
            @Param("filter") FilterDecision filter,
            @Param("sources") List<JobSource> sources);

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

    @Query("SELECT j.externalId FROM JobPosting j WHERE j.source = :source")
    Set<String> findExternalIdsBySourceAsSet(@Param("source") JobSource source);

    @Query("SELECT j.fingerprint FROM JobPosting j WHERE j.source = :source AND j.fingerprint IS NOT NULL")
    Set<String> findFingerprintsBySource(@Param("source") JobSource source);

    // Load fingerprints from all non-aggregator (ATS direct) sources for cross-source dedup matching
    @Query("SELECT DISTINCT j.fingerprint FROM JobPosting j WHERE j.fingerprint IS NOT NULL AND j.source NOT IN :aggregatorSources AND j.isActive = true")
    Set<String> findAtsFingerprintsExcludingSources(@Param("aggregatorSources") List<JobSource> aggregatorSources);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE JobPosting j SET j.isActive = false, j.deactivatedAt = :now " +
           "WHERE j.endpoint.id = :endpointId AND j.isActive = true AND j.externalId NOT IN :seenExternalIds")
    int bulkDeactivateByEndpointExcluding(
        @Param("endpointId") UUID endpointId,
        @Param("seenExternalIds") Collection<String> seenExternalIds,
        @Param("now") LocalDateTime now);

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
           "AND j.languageFilter = :filter " +
           "AND (" +
           "  (j.postedDate IS NOT NULL AND j.postedDate >= :since) " +
           "  OR (j.postedDate IS NULL AND j.source NOT IN :aggregatorSources AND j.discoveredDate >= :since)" +
           "  OR (j.postedDate IS NULL AND j.source IN :aggregatorSources AND j.discoveredDate = :today)" +
           ")")
    Page<JobPosting> findRecentlyPostedJobs(@Param("filter") FilterDecision filter,
                                           @Param("since") LocalDate since,
                                           @Param("today") LocalDate today,
                                           @Param("aggregatorSources") List<JobSource> aggregatorSources,
                                           Pageable pageable);

    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.visaSponsorship = 'PENDING' AND j.discoveredDate < :cutoff")
    List<JobPosting> findActivePendingVisaJobsDiscoveredBefore(@Param("cutoff") LocalDate cutoff);
}
