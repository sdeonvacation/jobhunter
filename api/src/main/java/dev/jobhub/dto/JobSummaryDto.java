package dev.jobhub.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JobSummaryDto(
        UUID id,
        String title,
        String companyName,
        String location,
        String remoteType,
        int opportunityScore,
        int matchScore,
        String recommendation,
        List<String> topSkills,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        LocalDate postedDate,
        String source,
        String applyUrl,
        boolean applied
) {}
