package dev.jobhunter.model.enums;

import java.util.List;

public enum JobSource {
    // ATS platforms (1:1 from AtsType)
    GREENHOUSE, LEVER, LEVER_EU, ASHBY, SMARTRECRUITERS, WORKABLE, WORKDAY, WORKDAY_PROTECTED,
    PERSONIO, BREEZY, RECRUITEE, JOIN, BAMBOOHR, TEAMTAILOR, SUCCESSFACTORS, ICIMS, JOBVITE, PINPOINT, STEPSTONE,
    // Aggregator sources
    LINKEDIN, INDEED, BERLIN_STARTUP_JOBS, ARBEITNOW, CAREERS_IN_GOTHENBURG, INSTAFFO, BUILTIN_EUROPE, JOBGETHER,
    // Direct career pages
    DIRECT,
    // Fallback
    UNKNOWN;

    private static final List<JobSource> AGGREGATORS = List.of(LINKEDIN, INDEED, BERLIN_STARTUP_JOBS, ARBEITNOW, CAREERS_IN_GOTHENBURG, INSTAFFO, BUILTIN_EUROPE, JOBGETHER);

    public boolean isAggregator() {
        return AGGREGATORS.contains(this);
    }

    public static List<JobSource> aggregators() {
        return AGGREGATORS;
    }

    public static JobSource fromAtsType(AtsType atsType) {
        return switch (atsType) {
            case CUSTOM -> DIRECT;
            case LINKEDIN -> LINKEDIN;
            case INDEED -> INDEED;
            case ARBEITNOW -> ARBEITNOW;
            case JOBGETHER -> JOBGETHER;
            default -> JobSource.valueOf(atsType.name());
        };
    }
}
