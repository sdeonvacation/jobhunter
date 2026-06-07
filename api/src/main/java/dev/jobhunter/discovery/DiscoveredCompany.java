package dev.jobhunter.discovery;

public record DiscoveredCompany(
        String companyName,
        String sourceJobTitle,
        String sourceUrl,
        String careerUrlHint
) {}
