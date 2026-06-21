package dev.jobhunter.filter.visa;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Orchestrates visa detection: regex first, AI fallback if inconclusive and enabled.
 */
@Slf4j
@Component
public class VisaDetectionChain {

    private final RegexVisaDetectionStrategy regexStrategy;
    private final AiVisaDetectionStrategy aiStrategy;
    private final boolean aiEnabled;

    public VisaDetectionChain(RegexVisaDetectionStrategy regexStrategy,
                              AiVisaDetectionStrategy aiStrategy,
                              PersonalProfileLoader profileLoader) {
        this.regexStrategy = regexStrategy;
        this.aiStrategy = aiStrategy;

        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.FilterConfig filters = profile.filters();
        PersonalProfile.VisaSponsorshipFilterConfig visaConfig =
                (filters != null) ? filters.visaSponsorship() : null;
        PersonalProfile.AiFallbackConfig aiFallback =
                (visaConfig != null) ? visaConfig.aiFallback() : null;

        this.aiEnabled = (aiFallback != null) && aiFallback.enabled();
    }

    /**
     * Evaluate visa sponsorship signal in a job description.
     * Runs regex detection first; falls back to AI if result is inconclusive.
     */
    public VisaDetectionResult evaluate(String description) {
        VisaDetectionResult regexResult = regexStrategy.detect(description);

        if (regexResult.isDefinitive()) {
            log.debug("Visa detection resolved by regex: {}", regexResult.status());
            return regexResult;
        }

        if (aiEnabled) {
            log.debug("Regex inconclusive, falling back to AI");
            return aiStrategy.detect(description);
        }

        return VisaDetectionResult.unknown("no signal detected");
    }
}
