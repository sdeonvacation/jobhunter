package dev.jobhunter.people.dto;

public record CompanyIntelligenceDto(
        String companyId,
        String industry,
        Integer employeeCount,
        String specialties,
        Integer hiringVelocity,
        String employeeGrowth,
        String fundingStage,
        VisaSignalsDto visaSignals,
        String lastEnrichedAt
) {}
