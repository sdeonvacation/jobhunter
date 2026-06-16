package dev.jobhunter.people.dto;

public record SendMessageRequest(
        String content,
        String channel,
        String messageType
) {}
