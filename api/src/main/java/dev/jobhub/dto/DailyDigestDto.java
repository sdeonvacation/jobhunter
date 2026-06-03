package dev.jobhub.dto;

import java.time.LocalDate;
import java.util.List;

public record DailyDigestDto(
        LocalDate date,
        int newJobsCount,
        List<JobSummaryDto> topOpportunities,
        int activeApplications,
        int companiesMonitored
) {}
