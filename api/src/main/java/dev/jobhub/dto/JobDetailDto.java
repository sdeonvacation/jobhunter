package dev.jobhub.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JobDetailDto(
        UUID id,
        String title,
        String companyName,
        UUID companyId,
        String location,
        String locationCity,
        String locationCountry,
        String remoteType,
        String employmentType,
        String description,
        String applyUrl,
        LocalDate postedDate,
        LocalDate discoveredDate,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String source,
        String externalId,
        int opportunityScore,
        int matchScore,
        String recommendation,
        List<String> matchedSkills,
        List<String> missingSkills,
        TechStackDto techStack,
        String recruiterName,
        String recruiterEmail,
        ScoreBreakdownDto scoreBreakdown
) {}
