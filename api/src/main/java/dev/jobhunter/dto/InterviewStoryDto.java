package dev.jobhunter.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record InterviewStoryDto(
        UUID id,
        String situation,
        String task,
        String action,
        String result,
        String reflection,
        List<String> tags,
        List<String> skills,
        UUID sourceJobId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
