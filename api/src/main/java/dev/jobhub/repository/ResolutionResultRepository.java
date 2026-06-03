package dev.jobhub.repository;

import dev.jobhub.model.ResolutionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResolutionResultRepository extends JpaRepository<ResolutionResult, UUID> {

    List<ResolutionResult> findByCompanyId(UUID companyId);

    List<ResolutionResult> findByNeedsManualReviewTrue();
}
