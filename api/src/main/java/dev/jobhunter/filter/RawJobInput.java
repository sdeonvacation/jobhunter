package dev.jobhunter.filter;

public record RawJobInput(
        String title,
        String description,
        String location,
        String companyName
) {}
