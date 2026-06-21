package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Filters jobs based on visa sponsorship likelihood.
 * German jobs bypass entirely. EU-country jobs run detection chain
 * (or defer to enrichment if aggregator-sourced with no description).
 */
@Slf4j
@Component
public class VisaSponsorshipFilter {

    private final VisaDetectionChain detectionChain;
    private final List<Pattern> dePatterns;
    private final List<Pattern> remoteEuPatterns;
    private final List<Pattern> targetCountryPatterns;
    private final String unknownAction;

    // Default DE patterns if config absent
    private static final List<String> DEFAULT_DE_PATTERNS = List.of(
            "\\bgermany\\b", "\\bdeutschland\\b", "\\bberlin\\b", "\\bmunich\\b",
            "\\bm[uü]nchen\\b", "\\bhamburg\\b", "\\bfrankfurt\\b"
    );

    public VisaSponsorshipFilter(VisaDetectionChain detectionChain,
                                 PersonalProfileLoader profileLoader) {
        this.detectionChain = detectionChain;

        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.FilterConfig filters = profile.filters();
        PersonalProfile.VisaSponsorshipFilterConfig visaConfig =
                (filters != null) ? filters.visaSponsorship() : null;

        if (visaConfig != null) {
            this.dePatterns = compilePatterns(
                    visaConfig.dePatterns().isEmpty() ? DEFAULT_DE_PATTERNS : visaConfig.dePatterns());
            this.remoteEuPatterns = compilePatterns(visaConfig.remoteEuPatterns());
            this.targetCountryPatterns = visaConfig.targetCountries().stream()
                    .map(c -> c.startsWith("\\b") ? c : "\\b" + c + "\\b")
                    .map(c -> Pattern.compile(c, Pattern.CASE_INSENSITIVE))
                    .collect(Collectors.toList());
            this.unknownAction = visaConfig.unknownAction() != null
                    ? visaConfig.unknownAction() : "skip";
        } else {
            this.dePatterns = compilePatterns(DEFAULT_DE_PATTERNS);
            this.remoteEuPatterns = List.of();
            this.targetCountryPatterns = List.of();
            this.unknownAction = "skip";
        }
    }

    /**
     * Evaluate visa sponsorship for a job.
     *
     * @param location     raw location string from the job
     * @param description  job description text (may be null/short for aggregators)
     * @param isAggregator true if sourced from aggregator (description may be stub)
     * @return filter result with visa status
     */
    public VisaFilterResult filter(String location, String description, boolean isAggregator) {
        String country = extractCountry(location);

        // German jobs bypass visa filter entirely
        if ("germany".equals(country)) {
            return VisaFilterResult.bypass();
        }

        // Job is in a target EU country or matches remote-EU pattern
        if (country != null || matchesRemoteEu(location)) {
            // Aggregator with no/short description: defer to enrichment
            if (isAggregator && (description == null || description.length() < 200)) {
                log.debug("Visa filter: deferring to enrichment for aggregator job, location={}", location);
                return VisaFilterResult.keep(VisaSponsorship.PENDING);
            }

            // Run detection chain on description
            VisaDetectionResult detection = detectionChain.evaluate(description != null ? description : "");

            return switch (detection.status()) {
                case CONFIRMED, LIKELY -> VisaFilterResult.keep(detection.status());
                case REJECTED -> VisaFilterResult.skip(
                        "visa: " + detection.reason(), VisaSponsorship.REJECTED);
                default -> applyUnknownAction(detection);
            };
        }

        // Location not in any configured list — bypass (let location filter handle rejection)
        return VisaFilterResult.bypass();
    }

    /**
     * Extract country from location string by matching against configured patterns.
     * Returns "germany" for DE matches, the matching target country name, or null.
     */
    public String extractCountry(String location) {
        if (location == null || location.isBlank()) return null;
        String lower = location.toLowerCase();

        for (Pattern p : dePatterns) {
            if (p.matcher(lower).find()) return "germany";
        }
        for (Pattern p : targetCountryPatterns) {
            if (p.matcher(lower).find()) return p.pattern();
        }
        return null;
    }

    private boolean matchesRemoteEu(String location) {
        if (location == null || location.isBlank() || remoteEuPatterns.isEmpty()) return false;
        String trimmed = location.trim();
        for (Pattern p : remoteEuPatterns) {
            if (p.matcher(trimmed).find()) return true;
        }
        return false;
    }

    private VisaFilterResult applyUnknownAction(VisaDetectionResult detection) {
        if ("keep".equalsIgnoreCase(unknownAction)) {
            return VisaFilterResult.keep(VisaSponsorship.UNKNOWN);
        }
        return VisaFilterResult.skip("visa: " + detection.reason(), VisaSponsorship.UNKNOWN);
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        return patterns.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .collect(Collectors.toList());
    }
}
