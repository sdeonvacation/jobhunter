package dev.jobhunter.filter;

import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobFilterChainTest {

    @Mock private LanguageFilter languageFilter;
    @Mock private RoleRelevanceFilter roleRelevanceFilter;
    @Mock private LocationFilter locationFilter;
    @Mock private VisaSponsorshipFilter visaSponsorshipFilter;
    @Mock private YoeFilter yoeFilter;
    @Mock private DeduplicationFilter deduplicationFilter;
    @Mock private JobPostingRepository jobPostingRepository;

    private JobFilterChain chain;

    @BeforeEach
    void setUp() {
        chain = new JobFilterChain(languageFilter, roleRelevanceFilter, locationFilter,
                visaSponsorshipFilter, yoeFilter, deduplicationFilter, jobPostingRepository);
    }

    private RawJobInput input(String title, String desc, String location, String company) {
        return new RawJobInput(title, desc, location, company);
    }

    // --- Happy path ---

    @Test
    void happyPath_allPass_keepWithNullReason() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilterExcludingSources(
                anyString(), any(), any()))
                .thenReturn(Optional.empty());

        FilterChainResult result = chain.apply(
                input("Engineer", "Java role", "Berlin", "TestCo"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    // --- Short-circuit tests ---

    @Test
    void languageFilter_skip_shortCircuitsRole() {
        when(languageFilter.filter(anyString(), anyString()))
                .thenReturn(FilterResult.skip("German JD"));

        FilterChainResult result = chain.apply(
                input("Entwickler", "Wir suchen", "Berlin", "Co"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("German JD");
        verify(roleRelevanceFilter, never()).filter(anyString());
    }

    @Test
    void roleFilter_skip_shortCircuitsLocation() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.skip("manager role"));

        FilterChainResult result = chain.apply(
                input("HR Manager", "desc", "Berlin", "Co"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("manager role");
        verify(locationFilter, never()).filter(anyString());
    }

    @Test
    void locationFilter_skip_shortCircuitsVisa() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.skip("bad location"));

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "US only", "Co"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        verify(visaSponsorshipFilter, never()).filter(anyString(), anyString(), anyBoolean());
    }

    @Test
    void visaFilter_rejected_skipWithVisaReason() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.skip("visa: no sponsorship", VisaSponsorship.REJECTED));

        FilterChainResult result = chain.apply(
                input("Engineer", "No visa sponsorship", "Dublin, Ireland", "Co"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("visa: no sponsorship");
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.REJECTED);
        verify(yoeFilter, never()).extractYoe(anyString());
    }

    @Test
    void yoeFilter_skip_shortCircuitsDedup() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(10);
        when(yoeFilter.filter(10)).thenReturn(FilterResult.skip("requires 10+ years experience"));

        FilterChainResult result = chain.apply(
                input("Staff Engineer", "10+ years needed", "Berlin", "Co"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("requires 10+ years experience");
        verify(deduplicationFilter, never()).generateFingerprint(anyString(), anyString(), anyString());
    }

    // --- visaExempt flag ---

    @Test
    void visaExempt_true_skipVisaFilter_returnsLikely() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilterExcludingSources(
                anyString(), any(), any()))
                .thenReturn(Optional.empty());

        FilterChainResult result = chain.apply(
                input("Engineer", "Great role", "Amsterdam", "Co"), false, true);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.LIKELY);
        verify(visaSponsorshipFilter, never()).filter(anyString(), anyString(), anyBoolean());
    }

    // --- Deduplication ---

    @Test
    void dedup_duplicateFound_skip() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("dup-fp");
        var dupJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(dev.jobhunter.model.enums.JobSource.GREENHOUSE)
                .build();
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilterExcludingSources(
                eq("dup-fp"), eq(FilterDecision.KEEP), any()))
                .thenReturn(Optional.of(dupJob));

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "Berlin", "TestCo"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).startsWith("duplicate of");
    }

    @Test
    void dedup_blankCompanyName_skipsDedupCheck() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), any(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(any())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "Berlin", ""), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        verify(deduplicationFilter, never()).generateFingerprint(anyString(), anyString(), anyString());
    }

    // --- Fail-open on exception ---

    @Test
    void filterChain_exception_failOpen() {
        when(languageFilter.filter(anyString(), anyString()))
                .thenThrow(new RuntimeException("unexpected error"));

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "Berlin", "Co"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    // --- isAggregator forwarded to visa filter ---

    @Test
    void isAggregator_true_forwardedToVisaFilter() {
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), eq(true)))
                .thenReturn(VisaFilterResult.keep(VisaSponsorship.PENDING));
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(anyString(), any()))
                .thenReturn(Optional.empty());

        FilterChainResult result = chain.apply(
                input("Engineer", "Short stub", "Amsterdam, Netherlands", "Co"), true, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.visaSponsorship()).isEqualTo(VisaSponsorship.PENDING);
        verify(visaSponsorshipFilter).filter(anyString(), anyString(), eq(true));
    }

    // --- Null/blank input safety ---

    @Test
    void nullInputs_noNpe() {
        when(languageFilter.filter(isNull(), isNull())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(isNull())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(isNull())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(isNull(), isNull(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(isNull())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());

        FilterChainResult result = chain.apply(new RawJobInput(null, null, null, null), false, false);

        // blank companyName → dedup skipped; should KEEP without NPE
        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
    }

    // --- Tier-aware deduplication ---

    @Test
    void dedup_endpointJob_onlyAggregatorExists_keepNotSkipped() {
        // Endpoint job arrives; only an aggregator job has this fingerprint.
        // The endpoint must NOT be deduplicated — it should pass through so
        // CrawlService can supersede the aggregator.
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("agg-fp");
        // Endpoint dedup query returns empty — no endpoint job exists yet
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilterExcludingSources(
                eq("agg-fp"), eq(FilterDecision.KEEP), any()))
                .thenReturn(Optional.empty());

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "Berlin", "TestCo"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        // Aggregator-wide query should NOT be called for endpoint jobs
        verify(jobPostingRepository, never())
                .findFirstByFingerprintAndLanguageFilter(anyString(), any());
    }

    @Test
    void dedup_endpointJob_anotherEndpointExists_skip() {
        // Endpoint job arrives; another endpoint job already has this fingerprint → dedup.
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("endpoint-fp");
        var existingEndpointJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.GREENHOUSE)
                .build();
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilterExcludingSources(
                eq("endpoint-fp"), eq(FilterDecision.KEEP), any()))
                .thenReturn(Optional.of(existingEndpointJob));

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "Berlin", "TestCo"), false, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).startsWith("duplicate of");
    }

    @Test
    void dedup_aggregatorJob_endpointExists_skip() {
        // Aggregator job arrives; an endpoint job has the same fingerprint → dedup via
        // the broad findFirstByFingerprintAndLanguageFilter query.
        when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
        when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
        when(visaSponsorshipFilter.filter(anyString(), anyString(), anyBoolean()))
                .thenReturn(VisaFilterResult.bypass());
        when(yoeFilter.extractYoe(anyString())).thenReturn(null);
        when(yoeFilter.filter(null)).thenReturn(FilterResult.keep());
        when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString()))
                .thenReturn("fp");
        var existingEndpointJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .source(JobSource.GREENHOUSE)
                .build();
        when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(
                eq("fp"), eq(FilterDecision.KEEP)))
                .thenReturn(Optional.of(existingEndpointJob));

        FilterChainResult result = chain.apply(
                input("Engineer", "desc", "Berlin", "TestCo"), true, false);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).startsWith("duplicate of");
        // Endpoint-only query should NOT be called for aggregator jobs
        verify(jobPostingRepository, never())
                .findFirstByFingerprintAndLanguageFilterExcludingSources(anyString(), any(), any());
    }
}

