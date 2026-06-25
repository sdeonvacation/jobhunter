package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Filters jobs based on visa sponsorship signals in the job description.
 * Location/geo-eligibility is handled upstream by the location filter.
 */
@Slf4j
@Component
public class VisaSponsorshipFilterImpl implements VisaSponsorshipFilter {

    private final VisaDetectionChain detectionChain;
    private final String unknownAction;

    public VisaSponsorshipFilterImpl(VisaDetectionChain detectionChain,
                                     PersonalProfileLoader profileLoader) {
        this.detectionChain = detectionChain;

        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.VisaSponsorshipFilterConfig visaConfig =
                (profile.filters() != null) ? profile.filters().visaSponsorship() : null;
        this.unknownAction = (visaConfig != null && visaConfig.unknownAction() != null)
                ? visaConfig.unknownAction() : "skip";
    }

    @Override
    public VisaFilterResult filter(String description, boolean isAggregator) {
        // Defer: aggregator job with no/short description — enrich later
        if (isAggregator && (description == null || description.length() < 200)) {
            log.debug("Visa filter: deferring to enrichment for aggregator job with short/null description");
            return VisaFilterResult.keep(VisaSponsorship.PENDING);
        }

        VisaDetectionResult detection = detectionChain.evaluate(description != null ? description : "");

        return switch (detection.status()) {
            case CONFIRMED, LIKELY -> VisaFilterResult.keep(detection.status());
            case REJECTED -> VisaFilterResult.skip("visa: " + detection.reason(), VisaSponsorship.REJECTED);
            default -> applyUnknownAction(detection);
        };
    }

    private VisaFilterResult applyUnknownAction(VisaDetectionResult detection) {
        if ("keep".equalsIgnoreCase(unknownAction)) {
            return VisaFilterResult.keep(VisaSponsorship.UNKNOWN);
        }
        return VisaFilterResult.skip("visa: " + detection.reason(), VisaSponsorship.UNKNOWN);
    }
}
