package dev.jobhunter.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record FollowUpDto(
        UUID id,
        UUID jobId,
        String jobTitle,
        String companyName,
        LocalDate scheduledDate,
        LocalDate sentDate,
        int count,
        String status,
        String notes,
        LocalDateTime createdAt
) {}
