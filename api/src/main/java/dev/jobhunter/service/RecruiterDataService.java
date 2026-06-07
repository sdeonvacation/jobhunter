package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.ai.RecruiterExtraction;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

/**
 * Manages recruiter PII with GDPR-compliant TTL and purge.
 */
@Slf4j
@Service
public class RecruiterDataService {

    private static final String RECRUITER_EXTRACTION_PROMPT = """
            Extract the recruiter or hiring manager contact information from this job posting.
            Look for names and email addresses of the person posting the job or managing recruitment.
            If no recruiter information is found, return empty strings.
            Return: name (full name) and email (email address).
            """;

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final int ttlDays;

    public RecruiterDataService(AiProvider aiProvider,
                                JobPostingRepository jobPostingRepository,
                                @Value("${gdpr.recruiter-ttl-days:90}") int ttlDays) {
        this.aiProvider = aiProvider;
        this.jobPostingRepository = jobPostingRepository;
        this.ttlDays = ttlDays;
    }

    /**
     * Extract recruiter info from job description and set GDPR expiry.
     */
    @Transactional
    public boolean extractRecruiterInfo(JobPosting job) {
        if (job.getRecruiterName() != null || job.getRecruiterEmail() != null) {
            return false; // Already has recruiter data
        }

        if (!aiProvider.isAvailable() || job.getDescription() == null) {
            return false;
        }

        try {
            RecruiterExtraction result = aiProvider.extract(
                    RECRUITER_EXTRACTION_PROMPT, job.getDescription(), RecruiterExtraction.class);

            if (result == null || (isBlank(result.name()) && isBlank(result.email()))) {
                return false;
            }

            if (!isBlank(result.name())) {
                job.setRecruiterName(result.name());
            }
            if (!isBlank(result.email())) {
                job.setRecruiterEmail(result.email());
            }
            job.setRecruiterDataExpiresAt(LocalDateTime.now().plusDays(ttlDays));

            jobPostingRepository.save(job);
            log.info("Extracted recruiter info for job {}: {}", job.getId(), result.name());
            return true;
        } catch (Exception e) {
            log.error("Recruiter extraction failed for job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Purge expired recruiter PII data. GDPR compliance.
     * Iterates all active jobs and clears PII where dataExpiresAt has passed.
     */
    @Transactional
    public int purgeExpiredData() {
        LocalDateTime now = LocalDateTime.now();
        int purged = 0;
        int page = 0;
        int pageSize = 100;

        while (true) {
            var jobs = jobPostingRepository.findByIsActiveTrue(
                    PageRequest.of(page, pageSize));

            for (JobPosting job : jobs.getContent()) {
                if (job.getRecruiterDataExpiresAt() != null && job.getRecruiterDataExpiresAt().isBefore(now)) {
                    job.setRecruiterName(null);
                    job.setRecruiterEmail(null);
                    job.setRecruiterDataExpiresAt(null);
                    jobPostingRepository.save(job);
                    purged++;
                }
            }

            if (!jobs.hasNext()) break;
            page++;
        }

        if (purged > 0) {
            log.info("GDPR purge: cleared recruiter data from {} expired job postings", purged);
        }
        return purged;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
