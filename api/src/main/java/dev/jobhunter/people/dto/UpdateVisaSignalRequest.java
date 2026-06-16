package dev.jobhunter.people.dto;

public record UpdateVisaSignalRequest(
        String signal,
        boolean value
) {}
