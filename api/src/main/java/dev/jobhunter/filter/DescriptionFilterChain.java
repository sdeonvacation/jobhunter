package dev.jobhunter.filter;

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

    public DescriptionFilterChain(LanguageFilter languageFilter,
                                  YoeFilter yoeFilter,
                                  VisaSponsorshipFilter visaSponsorshipFilter) {
        this.languageFilter = languageFilter;
        this.yoeFilter = yoeFilter;
        this.visaSponsorshipFilter = visaSponsorshipFilter;
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
            log.debug("Post-description language SKIP: job={} reason={}", job.getExternalId(), langResult.reason());
            return;
        }

        Integer yoe = yoeFilter.extractYoe(description);
        job.setRequiredYoe(yoe);
        FilterResult yoeResult = yoeFilter.filter(yoe);
        if (yoeResult.decision() == FilterDecision.SKIP) {
            job.setLanguageFilter(FilterDecision.SKIP);
            job.setFilterReason(yoeResult.reason());
            log.debug("Post-description YOE SKIP: job={} yoe={} reason={}", job.getExternalId(), yoe, yoeResult.reason());
            return;
        }

        if (job.getVisaSponsorship() == VisaSponsorship.PENDING) {
            // isAggregator=false: force detection, no deferral now that we have real content
            VisaFilterResult visaResult = visaSponsorshipFilter.filter(job.getLocation(), description, false);
            job.setVisaSponsorship(visaResult.visaSponsorship());
            if (visaResult.decision() == FilterDecision.SKIP) {
                job.setLanguageFilter(FilterDecision.SKIP);
                job.setFilterReason(visaResult.reason());
                log.debug("Post-description visa SKIP: job={} reason={}", job.getExternalId(), visaResult.reason());
            }
        }
    }
}
