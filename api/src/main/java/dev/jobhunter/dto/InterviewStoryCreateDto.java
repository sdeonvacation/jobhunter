package dev.jobhunter.dto;

import java.util.List;
import java.util.UUID;

public record InterviewStoryCreateDto(
        String situation,
        String task,
        String action,
        String result,
        String reflection,
        List<String> tags,
        List<String> skills,
        UUID sourceJobId
) {}
