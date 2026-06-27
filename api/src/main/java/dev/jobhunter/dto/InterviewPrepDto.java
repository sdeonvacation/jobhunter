package dev.jobhunter.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record InterviewPrepDto(
        UUID id,
        UUID jobId,
        String jobTitle,
        String companyName,
        List<Map<String, Object>> talkingPoints,
        List<UUID> mappedStoryIds,
        Map<String, Object> companyResearch,
        LocalDateTime preparedAt
) {}
