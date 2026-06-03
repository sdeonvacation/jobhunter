package dev.jobhub.service;

import dev.jobhub.model.JobPosting;
import dev.jobhub.repository.JobPostingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class JobService {

    private final JobPostingRepository jobPostingRepository;

    public JobService(JobPostingRepository jobPostingRepository) {
        this.jobPostingRepository = jobPostingRepository;
    }

    public Optional<JobPosting> findById(UUID id) {
        return jobPostingRepository.findById(id);
    }

    public Page<JobPosting> findActiveJobs(Pageable pageable) {
        return jobPostingRepository.findByIsActiveTrue(pageable);
    }

    public Page<JobPosting> findByCompany(UUID companyId, Pageable pageable) {
        return jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId, pageable);
    }
}
