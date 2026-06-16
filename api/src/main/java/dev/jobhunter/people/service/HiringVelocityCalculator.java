package dev.jobhunter.people.service;

import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculates hiring velocity for companies based on job posting frequency.
 */
@Slf4j
@Component
public class HiringVelocityCalculator {

    private static final int VELOCITY_WINDOW_DAYS = 30;

    private final JobPostingRepository jobPostingRepository;
    private final CompanyRepository companyRepository;

    public HiringVelocityCalculator(JobPostingRepository jobPostingRepository,
                                    CompanyRepository companyRepository) {
        this.jobPostingRepository = jobPostingRepository;
        this.companyRepository = companyRepository;
    }

    /**
     * Count jobs posted in the last 30 days for a specific company.
     */
    public int calculate(UUID companyId) {
        LocalDate cutoff = LocalDate.now().minusDays(VELOCITY_WINDOW_DAYS);
        List<JobPosting> activeJobs = jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId);

        int count = (int) activeJobs.stream()
                .filter(job -> job.getDiscoveredDate() != null)
                .filter(job -> !job.getDiscoveredDate().isBefore(cutoff))
                .count();

        log.debug("Hiring velocity for company {}: {} jobs in last {} days", companyId, count, VELOCITY_WINDOW_DAYS);
        return count;
    }

    /**
     * Calculate hiring velocity for all active companies.
     * Also persists the velocity value on each Company entity.
     */
    public Map<UUID, Integer> calculateAll() {
        List<Company> activeCompanies = companyRepository.findByIsActiveTrue();
        Map<UUID, Integer> velocityMap = new HashMap<>();

        for (Company company : activeCompanies) {
            int velocity = calculate(company.getId());
            velocityMap.put(company.getId(), velocity);

            if (!Objects.equals(company.getHiringVelocity(), velocity)) {
                company.setHiringVelocity(velocity);
                companyRepository.save(company);
            }
        }

        log.info("Calculated hiring velocity for {} companies", velocityMap.size());
        return velocityMap;
    }
}
