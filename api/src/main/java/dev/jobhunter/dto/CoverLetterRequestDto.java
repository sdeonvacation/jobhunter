package dev.jobhunter.dto;

import java.util.List;

public record CoverLetterRequestDto(
        String tone,
        String focus,
        List<String> angles
) {}
