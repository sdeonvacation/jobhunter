package dev.jobhunter.service;

import dev.jobhunter.dto.FollowUpDto;
import dev.jobhunter.dto.FollowUpScheduleDto;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.FollowUp;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FollowUpStatus;
import dev.jobhunter.repository.FollowUpRepository;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowUpCadenceServiceTest {

    @Mock private FollowUpRepository followUpRepository;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private PersonalProfileLoader profileLoader;

    private FollowUpCadenceService service;

    @BeforeEach
    void setUp() {
        PersonalProfile profile = new PersonalProfile(
                "Test", "Dev", 3, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null);
        when(profileLoader.getProfile()).thenReturn(profile);
        service = new FollowUpCadenceService(followUpRepository, jobPostingRepository, profileLoader);
    }

    @Test
    void scheduleFollowUp_jobNotFound_throws() {
        UUID jobId = UUID.randomUUID();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scheduleFollowUp(jobId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Job not found");
    }

    @Test
    void scheduleFollowUp_success_createsWithDefaultInterval() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().name("Acme Corp").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Engineer").company(company).build();
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));

        when(followUpRepository.save(any(FollowUp.class))).thenAnswer(invocation -> {
            FollowUp fu = invocation.getArgument(0);
            fu.setId(UUID.randomUUID());
            fu.setCreatedAt(LocalDateTime.now());
            return fu;
        });

        FollowUpDto result = service.scheduleFollowUp(jobId);

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.jobTitle()).isEqualTo("Engineer");
        assertThat(result.companyName()).isEqualTo("Acme Corp");
        assertThat(result.scheduledDate()).isEqualTo(LocalDate.now().plusDays(7));
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    void markSent_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(followUpRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSent(id, "notes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Follow-up not found");
    }

    @Test
    void markSent_firstAttempt_setsSentAndSchedulesNext() {
        UUID followUpId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().name("TestCo").build();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").company(company).build();

        FollowUp existing = FollowUp.builder()
                .id(followUpId)
                .job(job)
                .scheduledDate(LocalDate.now().minusDays(1))
                .count(1)
                .status(FollowUpStatus.PENDING)
                .build();
        existing.setCreatedAt(LocalDateTime.now().minusDays(7));

        when(followUpRepository.findById(followUpId)).thenReturn(Optional.of(existing));
        when(followUpRepository.save(any(FollowUp.class))).thenAnswer(invocation -> {
            FollowUp fu = invocation.getArgument(0);
            if (fu.getId() == null) {
                fu.setId(UUID.randomUUID());
                fu.setCreatedAt(LocalDateTime.now());
            }
            return fu;
        });

        FollowUpDto result = service.markSent(followUpId, "Sent via email");

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.sentDate()).isEqualTo(LocalDate.now());
        assertThat(result.notes()).isEqualTo("Sent via email");

        // Verify next follow-up scheduled
        ArgumentCaptor<FollowUp> captor = ArgumentCaptor.forClass(FollowUp.class);
        verify(followUpRepository, times(2)).save(captor.capture());

        FollowUp next = captor.getAllValues().get(1);
        assertThat(next.getCount()).isEqualTo(2);
        assertThat(next.getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(next.getScheduledDate()).isEqualTo(LocalDate.now().plusDays(7));
    }

    @Test
    void markSent_thirdAttempt_doesNotScheduleNext() {
        UUID followUpId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").build();

        FollowUp existing = FollowUp.builder()
                .id(followUpId)
                .job(job)
                .scheduledDate(LocalDate.now().minusDays(1))
                .count(3)
                .status(FollowUpStatus.PENDING)
                .build();
        existing.setCreatedAt(LocalDateTime.now().minusDays(28));

        when(followUpRepository.findById(followUpId)).thenReturn(Optional.of(existing));
        when(followUpRepository.save(any(FollowUp.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.markSent(followUpId, null);

        // Only one save (the existing one marked sent), no next scheduled
        verify(followUpRepository, times(1)).save(any(FollowUp.class));
    }

    @Test
    void getSchedule_withStatusFilter_queriesByStatus() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("SWE").build();
        FollowUp fu = FollowUp.builder()
                .id(UUID.randomUUID())
                .job(job)
                .scheduledDate(LocalDate.now().plusDays(3))
                .count(1)
                .status(FollowUpStatus.PENDING)
                .build();
        fu.setCreatedAt(LocalDateTime.now());

        when(followUpRepository.findByStatusOrderByScheduledDateAsc(
                eq(FollowUpStatus.PENDING), any(PageRequest.class)))
                .thenReturn(List.of(fu));
        when(followUpRepository.findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
                eq(FollowUpStatus.OVERDUE), any(LocalDate.class)))
                .thenReturn(List.of());

        FollowUpScheduleDto result = service.getSchedule("PENDING", 10);

        assertThat(result.followUps()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.overdueCount()).isEqualTo(0);
    }

    @Test
    void getSchedule_noFilter_queriesAll() {
        when(followUpRepository.findAllByOrderByScheduledDateAsc(any(PageRequest.class)))
                .thenReturn(List.of());
        when(followUpRepository.findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
                eq(FollowUpStatus.OVERDUE), any(LocalDate.class)))
                .thenReturn(List.of());

        FollowUpScheduleDto result = service.getSchedule(null, 5);

        assertThat(result.followUps()).isEmpty();
        assertThat(result.total()).isEqualTo(0);
        verify(followUpRepository).findAllByOrderByScheduledDateAsc(PageRequest.of(0, 5));
    }

    @Test
    void getOverdue_marksPendingAsOverdue() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("Backend").build();
        FollowUp overdue = FollowUp.builder()
                .id(UUID.randomUUID())
                .job(job)
                .scheduledDate(LocalDate.now().minusDays(2))
                .count(1)
                .status(FollowUpStatus.PENDING)
                .build();
        overdue.setCreatedAt(LocalDateTime.now().minusDays(9));

        when(followUpRepository.findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
                eq(FollowUpStatus.PENDING), any(LocalDate.class)))
                .thenReturn(List.of(overdue));
        when(followUpRepository.saveAll(anyList())).thenReturn(List.of(overdue));

        List<FollowUpDto> result = service.getOverdue();

        assertThat(result).hasSize(1);
        assertThat(overdue.getStatus()).isEqualTo(FollowUpStatus.OVERDUE);
        verify(followUpRepository).saveAll(List.of(overdue));
    }

    @Test
    void getOverdue_noneOverdue_returnsEmpty() {
        when(followUpRepository.findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
                eq(FollowUpStatus.PENDING), any(LocalDate.class)))
                .thenReturn(List.of());

        List<FollowUpDto> result = service.getOverdue();

        assertThat(result).isEmpty();
        verify(followUpRepository, never()).saveAll(anyList());
    }

    @Test
    void cancelFollowUp_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(followUpRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelFollowUp(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Follow-up not found");
    }

    @Test
    void cancelFollowUp_success_setsStatusCancelled() {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("QA").build();
        FollowUp fu = FollowUp.builder()
                .id(id)
                .job(job)
                .scheduledDate(LocalDate.now().plusDays(5))
                .count(1)
                .status(FollowUpStatus.PENDING)
                .build();

        when(followUpRepository.findById(id)).thenReturn(Optional.of(fu));
        when(followUpRepository.save(any(FollowUp.class))).thenReturn(fu);

        service.cancelFollowUp(id);

        assertThat(fu.getStatus()).isEqualTo(FollowUpStatus.CANCELLED);
        verify(followUpRepository).save(fu);
    }

    @Test
    void markSent_secondAttempt_schedulesThirdWithLongerInterval() {
        UUID followUpId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).title("Dev").build();

        FollowUp existing = FollowUp.builder()
                .id(followUpId)
                .job(job)
                .scheduledDate(LocalDate.now().minusDays(1))
                .count(2)
                .status(FollowUpStatus.PENDING)
                .build();
        existing.setCreatedAt(LocalDateTime.now().minusDays(14));

        when(followUpRepository.findById(followUpId)).thenReturn(Optional.of(existing));
        when(followUpRepository.save(any(FollowUp.class))).thenAnswer(invocation -> {
            FollowUp fu = invocation.getArgument(0);
            if (fu.getId() == null) {
                fu.setId(UUID.randomUUID());
                fu.setCreatedAt(LocalDateTime.now());
            }
            return fu;
        });

        service.markSent(followUpId, null);

        ArgumentCaptor<FollowUp> captor = ArgumentCaptor.forClass(FollowUp.class);
        verify(followUpRepository, times(2)).save(captor.capture());

        FollowUp next = captor.getAllValues().get(1);
        assertThat(next.getCount()).isEqualTo(3);
        // Third interval is 14 days (index 2 in default [7,7,14])
        assertThat(next.getScheduledDate()).isEqualTo(LocalDate.now().plusDays(14));
    }
}
