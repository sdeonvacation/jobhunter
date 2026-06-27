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
import dev.jobhunter.service.FollowUpCadenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutcomeServiceTest {

    @Mock private ApplicationRepository applicationRepository;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobOutcomeRepository jobOutcomeRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private FollowUpCadenceService followUpCadenceService;

    private OutcomeService outcomeService;

    @BeforeEach
    void setUp() {
        outcomeService = new OutcomeService(
                applicationRepository, jobPostingRepository,
                jobOutcomeRepository, companyRepository, followUpCadenceService);
    }

    @Test
    void markApplied_jobNotFound_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<Application> result = outcomeService.markApplied(jobId, "v1", "notes");

        assertThat(result).isEmpty();
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void markApplied_alreadyApplied_returnsExisting() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).build();
        Application existing = new Application();
        existing.setId(UUID.randomUUID());

        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(applicationRepository.findByJobId(jobId)).thenReturn(Optional.of(existing));

        Optional<Application> result = outcomeService.markApplied(jobId, "v1", null);

        assertThat(result).contains(existing);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void markApplied_newApplication_savesAndUpdatesCompany() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID())
                .totalApplications(5).totalInterviews(2).build();
        JobPosting job = JobPosting.builder().id(jobId).company(company).build();

        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(applicationRepository.findByJobId(jobId)).thenReturn(Optional.empty());
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Optional<Application> result = outcomeService.markApplied(jobId, "tailored-v2", "excited");

        assertThat(result).isPresent();
        Application app = result.get();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(app.getResumeVariant()).isEqualTo("tailored-v2");
        assertThat(app.getNotes()).isEqualTo("excited");
        assertThat(app.getJob()).isEqualTo(job);

        // Company application count incremented
        verify(companyRepository).save(company);
        assertThat(company.getTotalApplications()).isEqualTo(6);
    }

    @Test
    void recordOutcome_applicationNotFound_returnsEmpty() {
        UUID appId = UUID.randomUUID();
        when(applicationRepository.findById(appId)).thenReturn(Optional.empty());

        Optional<JobOutcome> result = outcomeService.recordOutcome(appId, OutcomeStage.INTERVIEW_1, "notes");

        assertThat(result).isEmpty();
    }

    @Test
    void recordOutcome_interviewStage_updatesCompanyInterviewStats() {
        UUID appId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID())
                .totalApplications(10).totalInterviews(3).build();
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).company(company).build();
        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.APPLIED);

        when(applicationRepository.findById(appId)).thenReturn(Optional.of(app));
        when(jobOutcomeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Optional<JobOutcome> result = outcomeService.recordOutcome(appId, OutcomeStage.INTERVIEW_1, "went well");

        assertThat(result).isPresent();
        assertThat(result.get().getStage()).isEqualTo(OutcomeStage.INTERVIEW_1);

        // Application status updated
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.INTERVIEWING);

        // Company interview stats updated
        assertThat(company.getTotalInterviews()).isEqualTo(4);
        assertThat(company.getInterviewRate()).isEqualTo(0.4);
        verify(companyRepository).save(company);
    }

    @Test
    void recordOutcome_nonInterviewStage_doesNotUpdateInterviewStats() {
        UUID appId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID())
                .totalApplications(5).totalInterviews(1).build();
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).company(company).build();
        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.APPLIED);

        when(applicationRepository.findById(appId)).thenReturn(Optional.of(app));
        when(jobOutcomeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        outcomeService.recordOutcome(appId, OutcomeStage.REJECTED, "no fit");

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(company.getTotalInterviews()).isEqualTo(1); // unchanged
    }

    @Test
    void recordOutcome_offerStage_setsOfferedStatus() {
        UUID appId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();
        Application app = new Application();
        app.setId(appId);
        app.setJob(job);
        app.setStatus(ApplicationStatus.INTERVIEWING);

        when(applicationRepository.findById(appId)).thenReturn(Optional.of(app));
        when(jobOutcomeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        outcomeService.recordOutcome(appId, OutcomeStage.OFFER, "100k");

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFERED);
    }

    @Test
    void getPipeline_noFilter_returnsAll() {
        List<Application> all = List.of(new Application(), new Application());
        when(applicationRepository.findAll()).thenReturn(all);

        List<Application> result = outcomeService.getPipeline(null);

        assertThat(result).hasSize(2);
        verify(applicationRepository).findAll();
    }

    @Test
    void getPipeline_withFilter_delegatesToRepo() {
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING))
                .thenReturn(List.of(new Application()));

        List<Application> result = outcomeService.getPipeline(ApplicationStatus.INTERVIEWING);

        assertThat(result).hasSize(1);
        verify(applicationRepository).findByStatus(ApplicationStatus.INTERVIEWING);
    }
}
