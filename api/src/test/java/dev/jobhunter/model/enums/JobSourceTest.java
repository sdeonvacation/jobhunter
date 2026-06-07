package dev.jobhunter.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobSourceTest {

    @Test
    void fromAtsType_custom_returnsDirect() {
        assertThat(JobSource.fromAtsType(AtsType.CUSTOM)).isEqualTo(JobSource.DIRECT);
    }

    @Test
    void fromAtsType_linkedin_returnsLinkedin() {
        assertThat(JobSource.fromAtsType(AtsType.LINKEDIN)).isEqualTo(JobSource.LINKEDIN);
    }

    @Test
    void fromAtsType_indeed_returnsIndeed() {
        assertThat(JobSource.fromAtsType(AtsType.INDEED)).isEqualTo(JobSource.INDEED);
    }

    @Test
    void fromAtsType_arbeitnow_returnsArbeitnow() {
        assertThat(JobSource.fromAtsType(AtsType.ARBEITNOW)).isEqualTo(JobSource.ARBEITNOW);
    }

    @Test
    void fromAtsType_greenhouse_returnsGreenhouse() {
        assertThat(JobSource.fromAtsType(AtsType.GREENHOUSE)).isEqualTo(JobSource.GREENHOUSE);
    }

    @Test
    void fromAtsType_unknown_returnsUnknown() {
        assertThat(JobSource.fromAtsType(AtsType.UNKNOWN)).isEqualTo(JobSource.UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(value = AtsType.class, names = {"CUSTOM", "LINKEDIN", "INDEED", "ARBEITNOW", "UNKNOWN"}, mode = EnumSource.Mode.EXCLUDE)
    void fromAtsType_allAtsTypes_mapByName(AtsType atsType) {
        JobSource result = JobSource.fromAtsType(atsType);
        assertThat(result.name()).isEqualTo(atsType.name());
    }

    @Test
    void jobSource_containsAllExpectedValues() {
        assertThat(JobSource.values()).contains(
                JobSource.GREENHOUSE, JobSource.LEVER, JobSource.LEVER_EU, JobSource.ASHBY,
                JobSource.SMARTRECRUITERS, JobSource.WORKABLE, JobSource.WORKDAY,
                JobSource.WORKDAY_PROTECTED, JobSource.PERSONIO, JobSource.BREEZY,
                JobSource.RECRUITEE, JobSource.JOIN, JobSource.BAMBOOHR, JobSource.TEAMTAILOR,
                JobSource.SUCCESSFACTORS, JobSource.ICIMS, JobSource.JOBVITE, JobSource.STEPSTONE,
                JobSource.LINKEDIN, JobSource.INDEED, JobSource.BERLIN_STARTUP_JOBS, JobSource.ARBEITNOW,
                JobSource.DIRECT, JobSource.UNKNOWN
        );
    }
}
