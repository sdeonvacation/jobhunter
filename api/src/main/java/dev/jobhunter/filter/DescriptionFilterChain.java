package dev.jobhunter.filter;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.VisaSponsorship;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Re-runs all description-dependent filters (language → YOE → visa) on a job
 * whose description was backfilled after initial ingestion.
 *
 * Callers: set description on the job, call refilter(), then save.
 * Job fields are mutated in-place; the caller owns persistence.
 */
@Slf4j
@Service
public class DescriptionFilterChain {

    private final LanguageFilter languageFilter;
    private final YoeFilter yoeFilter;
    private final VisaSponsorshipFilter visaSponsorshipFilter;
    private final CityCountryResolver cityCountryResolver;

    public DescriptionFilterChain(LanguageFilter languageFilter,
                                  YoeFilter yoeFilter,
                                  VisaSponsorshipFilter visaSponsorshipFilter,
                                  CityCountryResolver cityCountryResolver) {
        this.languageFilter = languageFilter;
        this.yoeFilter = yoeFilter;
        this.visaSponsorshipFilter = visaSponsorshipFilter;
        this.cityCountryResolver = cityCountryResolver;
    }

    /**
     * Re-evaluates description-dependent filters on a job that just had its description set.
     * No-op if description is null or job is already filtered out.
     * Updates job fields in-place; caller is responsible for saving.
     */
    public void refilter(JobPosting job) {
        String description = job.getDescription();
        if (description == null || job.getLanguageFilter() != FilterDecision.KEEP) {
            return;
        }

        FilterResult langResult = languageFilter.filter(job.getTitle(), description);
        if (langResult.decision() == FilterDecision.SKIP) {
            job.setLanguageFilter(FilterDecision.SKIP);
            job.setFilterReason(langResult.reason());
            clearDeferredVisa(job);
            log.debug("Post-description language SKIP: job={} reason={}", job.getExternalId(), langResult.reason());
            return;
        }

        Integer yoe = yoeFilter.extractYoe(description);
        job.setRequiredYoe(yoe);
        FilterResult yoeResult = yoeFilter.filter(yoe);
        if (yoeResult.decision() == FilterDecision.SKIP) {
            job.setLanguageFilter(FilterDecision.SKIP);
            job.setFilterReason(yoeResult.reason());
            clearDeferredVisa(job);
            log.debug("Post-description YOE SKIP: job={} yoe={} reason={}", job.getExternalId(), yoe, yoeResult.reason());
            return;
        }

        if (job.getVisaSponsorship() == VisaSponsorship.PENDING) {
            // Visa-exempt country (e.g. DE) — no description scan needed
            if (cityCountryResolver.isVisaExempt(job.getLocationCountry())) {
                job.setVisaSponsorship(VisaSponsorship.UNKNOWN);
                return;
            }
            // isAggregator=false: force detection, no deferral now that we have real content
            VisaFilterResult visaResult = visaSponsorshipFilter.filter(description, false);
            job.setVisaSponsorship(visaResult.visaSponsorship());
            if (visaResult.decision() == FilterDecision.SKIP) {
                job.setLanguageFilter(FilterDecision.SKIP);
                job.setFilterReason(visaResult.reason());
                log.debug("Post-description visa SKIP: job={} reason={}", job.getExternalId(), visaResult.reason());
            }
        }
    }

    /**
     * Clears a deferred visa flag once the job has been rejected on another
     * description-dependent filter.
     *
     * <p>Leaving PENDING set has two bad effects: the row is excluded from
     * enrichment (whose candidate query requires {@code language_filter = KEEP}) so
     * it can never be resolved, and the visa reaper later relabels it as
     * "pending timed out" - destroying the real rejection reason. The job is
     * already decided, so the visa status is moot; UNKNOWN keeps it out of both
     * the reaper and the visa badge.
     */
    private void clearDeferredVisa(JobPosting job) {
        if (job.getVisaSponsorship() == VisaSponsorship.PENDING) {
            job.setVisaSponsorship(VisaSponsorship.UNKNOWN);
        }
    }
}
