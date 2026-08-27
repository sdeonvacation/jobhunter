package dev.jobhunter.filter;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.filter.visa.VisaFilterResult;
import dev.jobhunter.filter.visa.VisaSponsorshipFilter;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class JobFilterChain {

    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final LocationFilter locationFilter;
    private final CityCountryResolver cityCountryResolver;
    private final VisaSponsorshipFilter visaSponsorshipFilter;
    private final YoeFilter yoeFilter;
    private final DeduplicationFilter deduplicationFilter;
    private final JobPostingRepository jobPostingRepository;

    public JobFilterChain(LanguageFilter languageFilter,
                          RoleRelevanceFilter roleRelevanceFilter,
                          LocationFilter locationFilter,
                          CityCountryResolver cityCountryResolver,
                          VisaSponsorshipFilter visaSponsorshipFilter,
                          YoeFilter yoeFilter,
                          DeduplicationFilter deduplicationFilter,
                          JobPostingRepository jobPostingRepository) {
        this.languageFilter = languageFilter;
        this.roleRelevanceFilter = roleRelevanceFilter;
        this.locationFilter = locationFilter;
        this.cityCountryResolver = cityCountryResolver;
        this.visaSponsorshipFilter = visaSponsorshipFilter;
        this.yoeFilter = yoeFilter;
        this.deduplicationFilter = deduplicationFilter;
        this.jobPostingRepository = jobPostingRepository;
    }

    /**
     * Apply lang→role→location→visa→yoe→dedup filter cascade.
     *
     * @param input        the raw job data
     * @param isAggregator true for aggregator-sourced jobs (enables visa PENDING deferral)
     * @param visaExempt   true for visa-exempt sources (expat portals); skips visa detection, sets LIKELY
     * @return FilterChainResult with decision, reason, visa status, and extracted YOE
     */
    public FilterChainResult apply(RawJobInput input, boolean isAggregator, boolean visaExempt) {
        try {
            // 1. Language filter
            FilterResult langResult = languageFilter.filter(input.title(), input.description());
            if (langResult.decision() == FilterDecision.SKIP) {
                return FilterChainResult.skip(langResult.reason());
            }

            // 2. Role filter
            FilterResult roleResult = roleRelevanceFilter.filter(input.title());
            if (roleResult.decision() == FilterDecision.SKIP) {
                return FilterChainResult.skip(roleResult.reason());
            }

            // 3. Location filter
            LocationFilterResult locationResult = locationFilter.filter(input.location());
            if (locationResult.decision() == FilterDecision.SKIP) {
                return FilterChainResult.skip(locationResult.reason());
            }

            // 4. Visa filter — skipped when source is visa-exempt OR location resolves to a visa-exempt country (DE)
            VisaSponsorship visaStatus;
            if (visaExempt || cityCountryResolver.isVisaExempt(locationResult.countryIso())) {
                visaStatus = VisaSponsorship.LIKELY;
            } else {
                VisaFilterResult visaResult = visaSponsorshipFilter.filter(input.description(), isAggregator);
                if (visaResult.decision() == FilterDecision.SKIP) {
                    return FilterChainResult.skip(visaResult.reason(), visaResult.visaSponsorship());
                }
                visaStatus = visaResult.visaSponsorship();
            }

            // 5. YOE filter
            Integer yoe = yoeFilter.extractYoe(input.description());
            FilterResult yoeResult = yoeFilter.filter(yoe);
            if (yoeResult.decision() == FilterDecision.SKIP) {
                return FilterChainResult.skip(yoeResult.reason());
            }

            // 6. Deduplication (only if companyName provided — avoids false positives)
            if (input.companyName() != null && !input.companyName().isBlank()) {
                String fingerprint = deduplicationFilter.generateFingerprint(
                        input.title(), input.companyName(), input.location());
                // Endpoint jobs only dedup against other endpoint jobs; if only an aggregator
                // job exists the endpoint wins — CrawlService will supersede it after save.
                // Aggregator jobs dedup against everything (endpoint and aggregator).
                Optional<dev.jobhunter.model.JobPosting> duplicate;
                if (isAggregator) {
                    duplicate = jobPostingRepository.findFirstByFingerprintAndLanguageFilter(
                            fingerprint, FilterDecision.KEEP);
                } else {
                    duplicate = jobPostingRepository.findFirstByFingerprintAndLanguageFilterExcludingSources(
                            fingerprint, FilterDecision.KEEP, JobSource.aggregators());
                }
                if (duplicate.isPresent()) {
                    return FilterChainResult.skip("duplicate of " + duplicate.get().getSource());
                }
            }

            return FilterChainResult.keep(visaStatus, yoe, locationResult.countryIso());

        } catch (Exception e) {
            log.warn("Filter chain exception (fail-open): {}", e.getMessage(), e);
            return FilterChainResult.keep(VisaSponsorship.UNKNOWN, null, null);
        }
    }
}
