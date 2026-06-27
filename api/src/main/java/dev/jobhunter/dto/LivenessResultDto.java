package dev.jobhunter.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LivenessResultDto(
        UUID jobId,
        String status,
        LocalDateTime checkedAt,
        String url,
        String reason
) {}
