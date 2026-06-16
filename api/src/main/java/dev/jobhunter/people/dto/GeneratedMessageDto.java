package dev.jobhunter.people.dto;

public record GeneratedMessageDto(
        String content,
        String variant,
        String contactId,
        String jobId,
        String modelUsed,
        int tokensUsed
) {}
