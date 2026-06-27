package dev.jobhunter.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CoverLetterGenerationDto(
        UUID id,
        UUID jobId,
        String content,
        String tone,
        String focus,
        List<String> angles,
        List<String> keywordsMirrored,
        int version,
        LocalDateTime generatedAt,
        LocalDateTime editedAt
) {}
