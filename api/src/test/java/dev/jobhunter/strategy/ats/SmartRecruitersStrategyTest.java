package dev.jobhunter.strategy.ats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmartRecruitersStrategyTest {

    private final SmartRecruitersStrategy strategy = new SmartRecruitersStrategy(null, null);

    @Test
    void parsePostingUrl_slugFormWithQuery_extractsSlugAndId() {
        var ref = SmartRecruitersStrategy.parsePostingUrl(
                "https://jobs.smartrecruiters.com/BlackbirdCollective/743999746512657-java-developer-germany?oga=true");

        assertThat(ref).isNotNull();
        assertThat(ref.companySlug()).isEqualTo("BlackbirdCollective");
        assertThat(ref.postingId()).isEqualTo("743999746512657");
    }

    @Test
    void parsePostingUrl_idOnly_extractsSlugAndId() {
        var ref = SmartRecruitersStrategy.parsePostingUrl(
                "https://jobs.smartrecruiters.com/ODIONGmbH/744000133036619");

        assertThat(ref).isNotNull();
        assertThat(ref.companySlug()).isEqualTo("ODIONGmbH");
        assertThat(ref.postingId()).isEqualTo("744000133036619");
    }

    @Test
    void parsePostingUrl_lowercaseDomain_matches() {
        var ref = SmartRecruitersStrategy.parsePostingUrl(
                "https://jobs.smartrecruiters.com/blackbirdcollective/743999746512657-java-developer-germany");

        assertThat(ref).isNotNull();
        assertThat(ref.companySlug()).isEqualTo("blackbirdcollective");
    }

    @Test
    void parsePostingUrl_oneclickUiRoute_returnsNull() {
        assertThat(SmartRecruitersStrategy.parsePostingUrl(
                "https://jobs.smartrecruiters.com/oneclick-ui/company/BlackbirdCollective/publication/ae694b29-ad54-43f0-aaf5-9bf982ce9009?dcr_ci=BlackbirdCollective"))
                .isNull();
    }

    @Test
    void parsePostingUrl_careersOrNonPostingUrl_returnsNull() {
        assertThat(SmartRecruitersStrategy.parsePostingUrl(
                "https://careers.smartrecruiters.com/BlackbirdCollective")).isNull();
        assertThat(SmartRecruitersStrategy.parsePostingUrl(null)).isNull();
        assertThat(SmartRecruitersStrategy.parsePostingUrl("https://example.com/jobs/123")).isNull();
    }

    @Test
    void isSmartRecruitersPostingUrl_trueForSlugForm_falseOtherwise() {
        assertThat(strategy.isSmartRecruitersPostingUrl(
                "https://jobs.smartrecruiters.com/BlackbirdCollective/743999746512657-x")).isTrue();
        assertThat(strategy.isSmartRecruitersPostingUrl(
                "https://jobs.smartrecruiters.com/oneclick-ui/company/A/publication/uuid")).isFalse();
    }
}