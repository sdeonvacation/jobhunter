package dev.jobhunter.filter;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DescriptionFilterChainTest {

    private LanguageFilter languageFilter;
    private YoeFilter yoeFilter;
    private VisaSponsorshipFilter visaSponsorshipFilter;
    private CityCountryResolver cityCountryResolver;
    private DescriptionFilterChain chain;

    @BeforeEach
    void setUp() {
        languageFilter = mock(LanguageFilter.class);
        yoeFilter = mock(YoeFilter.class);
        visaSponsorshipFilter = mock(VisaSponsorshipFilter.class);
        cityCountryResolver = mock(CityCountryResolver.class);
        chain = new DescriptionFilterChain(languageFilter, yoeFilter, visaSponsorshipFilter, cityCountryResolver);
    }

    private JobPosting pendingJob() {
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .externalId("job-1")
                .title("Backend Engineer")
                .description("A".repeat(600))
                .languageFilter(FilterDecision.KEEP)
                .visaSponsorship(VisaSponsorship.PENDING)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("language SKIP clears the deferred visa flag and keeps the language reason")
    void languageSkipClearsPending() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.skip("non-English JD (German)"));
        JobPosting job = pendingJob();

        chain.refilter(job);

        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(job.getFilterReason()).isEqualTo("non-English JD (German)");
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    @Test
    @DisplayName("YOE SKIP clears the deferred visa flag and keeps the YOE reason")
    void yoeSkipClearsPending() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(anyString())).thenReturn(9);
        when(yoeFilter.filter(9)).thenReturn(FilterResult.skip("requires 7+ years experience"));
        JobPosting job = pendingJob();

        chain.refilter(job);

        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(job.getFilterReason()).isEqualTo("requires 7+ years experience");
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    @Test
    @DisplayName("language and YOE pass -> PENDING is resolved from the description")
    void resolvesPendingWhenLanguageAndYoePass() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(anyString())).thenReturn(3);
        when(yoeFilter.filter(3)).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.keep(VisaSponsorship.LIKELY));
        JobPosting job = pendingJob();

        chain.refilter(job);

        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.LIKELY);
        assertThat(job.getFilterReason()).isNull();
    }

    @Test
    @DisplayName("visa SKIP with a real description records the visa reason")
    void visaSkipRecordsReason() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(anyString())).thenReturn(2);
        when(yoeFilter.filter(2)).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.skip("visa: no signal detected", VisaSponsorship.UNKNOWN));
        JobPosting job = pendingJob();

        chain.refilter(job);

        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(job.getFilterReason()).isEqualTo("visa: no signal detected");
        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
    }

    @Test
    @DisplayName("language SKIP does not disturb an already-resolved visa status")
    void languageSkipLeavesResolvedVisaUntouched() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.skip("non-English JD (Dutch)"));
        JobPosting job = pendingJob();
        job.setVisaSponsorship(VisaSponsorship.LIKELY);

        chain.refilter(job);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.LIKELY);
        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
    }

    @Test
    @DisplayName("visa-exempt country resolves PENDING to UNKNOWN without a description scan")
    void visaExemptCountryResolvesPending() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(anyString())).thenReturn(1);
        when(yoeFilter.filter(1)).thenReturn(FilterResult.keep());
        when(cityCountryResolver.isVisaExempt(any())).thenReturn(true);
        JobPosting job = pendingJob();

        chain.refilter(job);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
    }

    @Test
    @DisplayName("null description is a no-op")
    void nullDescriptionNoOp() {
        JobPosting job = pendingJob();
        job.setDescription(null);

        chain.refilter(job);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
        assertThat(job.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        verifyNoInteractions(languageFilter);
    }

    @Test
    @DisplayName("already-SKIP job is a no-op")
    void alreadySkippedNoOp() {
        JobPosting job = pendingJob();
        job.setLanguageFilter(FilterDecision.SKIP);

        chain.refilter(job);

        assertThat(job.getVisaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
        verifyNoInteractions(languageFilter);
    }
}
