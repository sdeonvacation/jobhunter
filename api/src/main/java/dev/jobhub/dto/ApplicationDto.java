package dev.jobhub.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ApplicationDto(
        UUID id,
        UUID jobId,
        String jobTitle,
        String companyName,
        String status,
        LocalDate appliedDate,
        String resumeVariant,
        String notes,
        List<OutcomeDto> outcomes,
        LocalDateTime createdAt
) {
    public record OutcomeDto(
            UUID id,
            String stage,
            LocalDateTime occurredAt,
            String notes
    ) {}
}
