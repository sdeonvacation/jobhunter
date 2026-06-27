package dev.jobhunter.service;

import dev.jobhunter.dto.FollowUpDto;
import dev.jobhunter.dto.FollowUpScheduleDto;
import dev.jobhunter.model.FollowUp;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FollowUpStatus;
import dev.jobhunter.repository.FollowUpRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class FollowUpCadenceService {

    private static final List<Integer> DEFAULT_INTERVALS = List.of(7, 7, 14);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final FollowUpRepository followUpRepository;
    private final JobPostingRepository jobPostingRepository;
    private final List<Integer> intervals;
    private final int maxAttempts;

    public FollowUpCadenceService(FollowUpRepository followUpRepository,
                                  JobPostingRepository jobPostingRepository,
                                  PersonalProfileLoader profileLoader) {
        this.followUpRepository = followUpRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.intervals = resolveIntervals(profileLoader);
        this.maxAttempts = resolveMaxAttempts(profileLoader);
    }

    @Transactional
    public FollowUpDto scheduleFollowUp(UUID jobId) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            throw new IllegalArgumentException("Job not found: " + jobId);
        }

        JobPosting job = jobOpt.get();
        int firstInterval = intervals.isEmpty() ? 7 : intervals.get(0);

        FollowUp followUp = FollowUp.builder()
                .job(job)
                .scheduledDate(LocalDate.now().plusDays(firstInterval))
                .count(1)
                .status(FollowUpStatus.PENDING)
                .build();

        FollowUp saved = followUpRepository.save(followUp);
        log.info("Scheduled follow-up #{} for job {} on {}",
                saved.getCount(), jobId, saved.getScheduledDate());

        return toDto(saved);
    }

    @Transactional
    public FollowUpDto markSent(UUID followUpId, String notes) {
        FollowUp followUp = followUpRepository.findById(followUpId)
                .orElseThrow(() -> new IllegalArgumentException("Follow-up not found: " + followUpId));

        followUp.setSentDate(LocalDate.now());
        followUp.setStatus(FollowUpStatus.SENT);
        followUp.setNotes(notes);
        followUpRepository.save(followUp);

        log.info("Marked follow-up {} as sent for job {}", followUpId, followUp.getJob().getId());

        // Schedule next follow-up if below max attempts
        if (followUp.getCount() < maxAttempts) {
            int nextCount = followUp.getCount() + 1;
            int intervalIndex = Math.min(nextCount - 1, intervals.size() - 1);
            int nextInterval = intervals.isEmpty() ? 7 : intervals.get(intervalIndex);

            FollowUp next = FollowUp.builder()
                    .job(followUp.getJob())
                    .scheduledDate(LocalDate.now().plusDays(nextInterval))
                    .count(nextCount)
                    .status(FollowUpStatus.PENDING)
                    .build();

            FollowUp savedNext = followUpRepository.save(next);
            log.info("Scheduled next follow-up #{} for job {} on {}",
                    savedNext.getCount(), followUp.getJob().getId(), savedNext.getScheduledDate());
        }

        return toDto(followUp);
    }

    @Transactional(readOnly = true)
    public FollowUpScheduleDto getSchedule(String statusFilter, int limit) {
        List<FollowUp> followUps;

        if (statusFilter != null && !statusFilter.isBlank()) {
            FollowUpStatus status = FollowUpStatus.valueOf(statusFilter.toUpperCase());
            followUps = followUpRepository.findByStatusOrderByScheduledDateAsc(
                    status, PageRequest.of(0, limit));
        } else {
            followUps = followUpRepository.findAllByOrderByScheduledDateAsc(
                    PageRequest.of(0, limit));
        }

        List<FollowUp> overdue = followUpRepository
                .findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
                        FollowUpStatus.OVERDUE, LocalDate.now().plusDays(1));

        List<FollowUpDto> dtos = followUps.stream().map(this::toDto).toList();
        return new FollowUpScheduleDto(dtos, dtos.size(), overdue.size());
    }

    @Transactional
    public List<FollowUpDto> getOverdue() {
        List<FollowUp> overdue = followUpRepository
                .findByStatusAndScheduledDateBeforeOrderByScheduledDateAsc(
                        FollowUpStatus.PENDING, LocalDate.now());

        if (overdue.isEmpty()) {
            return List.of();
        }

        for (FollowUp fu : overdue) {
            fu.setStatus(FollowUpStatus.OVERDUE);
        }
        followUpRepository.saveAll(overdue);
        log.info("Marked {} follow-ups as overdue", overdue.size());

        return overdue.stream().map(this::toDto).toList();
    }

    @Transactional
    public void cancelFollowUp(UUID id) {
        FollowUp followUp = followUpRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Follow-up not found: " + id));

        followUp.setStatus(FollowUpStatus.CANCELLED);
        followUpRepository.save(followUp);
        log.info("Cancelled follow-up {} for job {}", id, followUp.getJob().getId());
    }

    private FollowUpDto toDto(FollowUp followUp) {
        JobPosting job = followUp.getJob();
        String jobTitle = job != null ? job.getTitle() : null;
        String companyName = job != null && job.getCompany() != null
                ? job.getCompany().getName() : null;

        return new FollowUpDto(
                followUp.getId(),
                job != null ? job.getId() : null,
                jobTitle,
                companyName,
                followUp.getScheduledDate(),
                followUp.getSentDate(),
                followUp.getCount(),
                followUp.getStatus().name(),
                followUp.getNotes(),
                followUp.getCreatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> resolveIntervals(PersonalProfileLoader profileLoader) {
        try {
            PersonalProfile profile = profileLoader.getProfile();
            if (profile == null || profile.preferences() == null) {
                return DEFAULT_INTERVALS;
            }
            // Follow-up intervals would be in profile.yaml under follow-up.intervals
            // For now, use defaults since profile record doesn't include follow-up config yet
            return DEFAULT_INTERVALS;
        } catch (Exception e) {
            return DEFAULT_INTERVALS;
        }
    }

    private static int resolveMaxAttempts(PersonalProfileLoader profileLoader) {
        try {
            PersonalProfile profile = profileLoader.getProfile();
            if (profile == null) {
                return DEFAULT_MAX_ATTEMPTS;
            }
            return DEFAULT_MAX_ATTEMPTS;
        } catch (Exception e) {
            return DEFAULT_MAX_ATTEMPTS;
        }
    }
}
