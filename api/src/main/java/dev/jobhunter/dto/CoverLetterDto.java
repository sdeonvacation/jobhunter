package dev.jobhunter.dto;

import java.util.UUID;

public record CoverLetterDto(
        UUID jobId,
        String jobTitle,
        String companyName,
        String content,
        String tone
) {}
