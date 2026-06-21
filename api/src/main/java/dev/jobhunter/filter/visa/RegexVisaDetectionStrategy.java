package dev.jobhunter.filter.visa;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based visa sponsorship detection using configured positive/negative patterns.
 * Negative patterns override positive (checked first).
 */
@Slf4j
@Component
public class RegexVisaDetectionStrategy implements VisaDetectionStrategy {

    private final List<Pattern> negativePatterns;
    private final List<Pattern> positivePatterns;

    public RegexVisaDetectionStrategy(PersonalProfileLoader profileLoader) {
        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.FilterConfig filters = profile.filters();
        PersonalProfile.VisaSponsorshipFilterConfig visaConfig =
                (filters != null) ? filters.visaSponsorship() : null;

        if (visaConfig != null) {
            this.negativePatterns = compilePatterns(visaConfig.negativePatterns());
            this.positivePatterns = compilePatterns(visaConfig.positivePatterns());
        } else {
            this.negativePatterns = List.of();
            this.positivePatterns = List.of();
        }

        log.info("RegexVisaDetectionStrategy initialized: {} positive, {} negative patterns",
                this.positivePatterns.size(), this.negativePatterns.size());
    }

    @Override
    public VisaDetectionResult detect(String description) {
        if (description == null || description.isBlank()) {
            return VisaDetectionResult.unknown("empty description");
        }

        String text = description.toLowerCase();

        // Negative overrides positive — check first
        for (Pattern pattern : negativePatterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return VisaDetectionResult.rejected(0.9, "matched: " + pattern.pattern());
            }
        }

        for (Pattern pattern : positivePatterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return VisaDetectionResult.confirmed(0.9, "matched: " + pattern.pattern());
            }
        }

        return VisaDetectionResult.unclear();
    }

    private List<Pattern> compilePatterns(List<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return List.of();
        }
        return rawPatterns.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
