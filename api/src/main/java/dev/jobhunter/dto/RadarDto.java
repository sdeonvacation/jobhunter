package dev.jobhunter.dto;

import java.util.List;

public record RadarDto(
        List<JobSummaryDto> topOpportunities,
        List<CompanyTrendDto> heatingCompanies,
        List<CompanyTrendDto> coolingCompanies
) {
    public record CompanyTrendDto(
            String companyName,
            int recentJobCount,
            double avgOpportunityScore,
            String trend
    ) {}
}
