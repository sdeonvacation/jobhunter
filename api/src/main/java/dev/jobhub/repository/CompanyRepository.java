package dev.jobhub.repository;

import dev.jobhub.model.Company;
import dev.jobhub.model.enums.CompanyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByNormalizedName(String normalizedName);

    List<Company> findByStatus(CompanyStatus status);

    List<Company> findByIsActiveTrue();

    @Query("SELECT c FROM Company c WHERE c.status = :status AND c.careerEndpoints IS EMPTY")
    List<Company> findByStatusAndNoEndpoints(@Param("status") CompanyStatus status, Pageable pageable);

    @Query("SELECT c FROM Company c WHERE c.isActive = true AND LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Company> findByIsActiveTrueAndNameContaining(@Param("search") String search, Pageable pageable);

    @Query("SELECT c FROM Company c WHERE c.status = :status AND LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Company> findByStatusAndNameContaining(@Param("status") CompanyStatus status, @Param("search") String search, Pageable pageable);

    Page<Company> findByIsActiveTrue(Pageable pageable);

    Page<Company> findByStatus(CompanyStatus status, Pageable pageable);
}
