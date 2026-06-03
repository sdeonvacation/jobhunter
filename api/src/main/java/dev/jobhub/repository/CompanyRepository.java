package dev.jobhub.repository;

import dev.jobhub.model.Company;
import dev.jobhub.model.enums.CompanyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByNormalizedName(String normalizedName);

    List<Company> findByStatus(CompanyStatus status);

    List<Company> findByIsActiveTrue();
}
