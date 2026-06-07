package dev.jobhunter.model.enums;

public enum JobSource {
    // ATS platforms (1:1 from AtsType)
    GREENHOUSE, LEVER, LEVER_EU, ASHBY, SMARTRECRUITERS, WORKABLE, WORKDAY, WORKDAY_PROTECTED,
    PERSONIO, BREEZY, RECRUITEE, JOIN, BAMBOOHR, TEAMTAILOR, SUCCESSFACTORS, ICIMS, JOBVITE, STEPSTONE,
    // Aggregator sources
    LINKEDIN, INDEED, BERLIN_STARTUP_JOBS, ARBEITNOW,
    // Direct career pages
    DIRECT,
    // Fallback
    UNKNOWN;

    public static JobSource fromAtsType(AtsType atsType) {
        return switch (atsType) {
            case CUSTOM -> DIRECT;
            case LINKEDIN -> LINKEDIN;
            case INDEED -> INDEED;
            case ARBEITNOW -> ARBEITNOW;
            default -> JobSource.valueOf(atsType.name());
        };
    }
}
