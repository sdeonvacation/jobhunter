package dev.jobhunter.people.dto;

public record LinkedJobDto(
    String id,
    String title,
    String companyName,
    String location,
    String postedDate
) {}
