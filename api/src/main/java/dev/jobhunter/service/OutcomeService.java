package dev.jobhunter.service;

import dev.jobhunter.model.Application;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobOutcomeRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
public class OutcomeService {

    private final ApplicationRepository applicationRepository;
    private final JobPostingRepository jobPostingRepository;
    private final JobOutcomeRepository jobOutcomeRepository;
    private final CompanyRepository companyRepository;

    public OutcomeService(ApplicationRepository applicationRepository,
                          JobPostingRepository jobPostingRepository,
                          JobOutcomeRepository jobOutcomeRepository,
                          CompanyRepository companyRepository) {
        this.applicationRepository = applicationRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.jobOutcomeRepository = jobOutcomeRepository;
        this.companyRepository = companyRepository;
    }

    @Transactional
    public Optional<Application> markApplied(UUID jobId, String resumeVariant, String notes) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Cannot mark applied: job {} not found", jobId);
            return Optional.empty();
        }

        JobPosting job = jobOpt.get();

        // Check if already applied
        Optional<Application> existing = applicationRepository.findByJobId(jobId);
        if (existing.isPresent()) {
            log.info("Job {} already has an application", jobId);
            return existing;
        }

        Application application = new Application();
        application.setId(UUID.randomUUID());
        application.setJob(job);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setAppliedDate(LocalDate.now());
        application.setResumeVariant(resumeVariant);
        application.setNotes(notes);
        application.setCreatedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());

        Application saved = applicationRepository.save(application);

        // Update company stats
        updateCompanyApplicationCount(job.getCompany());

        log.info("Marked job {} as applied, application {}", jobId, saved.getId());
        return Optional.of(saved);
    }

    @Transactional
    public Optional<JobOutcome> recordOutcome(UUID applicationId, OutcomeStage stage, String notes) {
        Optional<Application> appOpt = applicationRepository.findById(applicationId);
        if (appOpt.isEmpty()) {
            log.warn("Cannot record outcome: application {} not found", applicationId);
            return Optional.empty();
        }

        Application application = appOpt.get();

        JobOutcome outcome = new JobOutcome();
        outcome.setId(UUID.randomUUID());
        outcome.setApplication(application);
        outcome.setStage(stage);
        outcome.setOccurredAt(LocalDate.now());
        outcome.setNotes(notes);
        outcome.setCreatedAt(LocalDateTime.now());

        JobOutcome saved = jobOutcomeRepository.save(outcome);

        // Update application status based on outcome stage
        updateApplicationStatus(application, stage);

        // If interview stage reached, update company interview stats
        if (isInterviewStage(stage)) {
            updateCompanyInterviewStats(application.getJob().getCompany());
        }

        log.info("Recorded outcome {} for application {}", stage, applicationId);
        return Optional.of(saved);
    }

    public List<Application> getPipeline(ApplicationStatus status) {
        if (status == null) {
            return applicationRepository.findAll();
        }
        return applicationRepository.findByStatus(status);
    }

    public List<JobOutcome> getOutcomes(UUID applicationId) {
        return jobOutcomeRepository.findByApplicationId(applicationId);
    }

    private void updateApplicationStatus(Application application, OutcomeStage stage) {
        ApplicationStatus newStatus = mapStageToStatus(stage);
        application.setStatus(newStatus);
        application.setUpdatedAt(LocalDateTime.now());
        applicationRepository.save(application);
    }

    private ApplicationStatus mapStageToStatus(OutcomeStage stage) {
        return switch (stage) {
            case APPLIED -> ApplicationStatus.APPLIED;
            case PHONE_SCREEN -> ApplicationStatus.PHONE_SCREEN;
            case INTERVIEW_1, INTERVIEW_2 -> ApplicationStatus.INTERVIEWING;
            case OFFER -> ApplicationStatus.OFFERED;
            case REJECTED -> ApplicationStatus.REJECTED;
            case WITHDRAWN -> ApplicationStatus.WITHDRAWN;
        };
    }

    private boolean isInterviewStage(OutcomeStage stage) {
        return stage == OutcomeStage.PHONE_SCREEN
                || stage == OutcomeStage.INTERVIEW_1
                || stage == OutcomeStage.INTERVIEW_2;
    }

    private void updateCompanyApplicationCount(Company company) {
        if (company == null) return;
        company.setTotalApplications(company.getTotalApplications() + 1);
        company.setUpdatedAt(LocalDateTime.now());
        companyRepository.save(company);
    }

    private void updateCompanyInterviewStats(Company company) {
        if (company == null) return;
        company.setTotalInterviews(company.getTotalInterviews() + 1);
        if (company.getTotalApplications() > 0) {
            company.setInterviewRate(
                    (double) company.getTotalInterviews() / company.getTotalApplications());
        }
        company.setUpdatedAt(LocalDateTime.now());
        companyRepository.save(company);
    }
}
