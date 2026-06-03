package dev.jobhub.resolution;

import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.Confidence;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects ATS type from URL using pattern matching and (optionally) HTML inspection.
 */
@Component
public class AtsDetector {

    private static final Map<Pattern, AtsType> URL_PATTERNS = Map.ofEntries(
            Map.entry(Pattern.compile("https?://([\\w-]+)\\.greenhouse\\.io.*"), AtsType.GREENHOUSE),
            Map.entry(Pattern.compile("https?://boards\\.greenhouse\\.io/([\\w-]+).*"), AtsType.GREENHOUSE),
            Map.entry(Pattern.compile("https?://jobs\\.lever\\.co/([\\w-]+).*"), AtsType.LEVER),
            Map.entry(Pattern.compile("https?://jobs\\.eu\\.lever\\.co/([\\w-]+).*"), AtsType.LEVER_EU),
            Map.entry(Pattern.compile("https?://jobs\\.ashbyhq\\.com/([\\w-]+).*"), AtsType.ASHBY),
            Map.entry(Pattern.compile("https?://[\\w-]+\\.wd\\d+\\.myworkdayjobs\\.com.*"), AtsType.WORKDAY),
            Map.entry(Pattern.compile("https?://www\\.stepstone\\.(de|at|nl|be)/.*"), AtsType.STEPSTONE)
    );

    public record DetectionResult(AtsType atsType, Confidence confidence, String slug) {}

    /**
     * Detect ATS from URL pattern matching (HIGH confidence).
     */
    public Optional<DetectionResult> detectFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        for (Map.Entry<Pattern, AtsType> entry : URL_PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(url);
            if (matcher.matches()) {
                String slug = matcher.groupCount() > 0 ? matcher.group(1) : null;
                return Optional.of(new DetectionResult(entry.getValue(), Confidence.HIGH, slug));
            }
        }

        return Optional.empty();
    }

    /**
     * Detect ATS from HTML content inspection (MEDIUM confidence).
     * Checks for common meta tags, script sources, and markers.
     */
    public Optional<DetectionResult> detectFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }

        String lower = html.toLowerCase();

        if (lower.contains("greenhouse.io") || lower.contains("boards.greenhouse")) {
            return Optional.of(new DetectionResult(AtsType.GREENHOUSE, Confidence.MEDIUM, null));
        }
        if (lower.contains("lever.co") || lower.contains("lever-jobs-iframe")) {
            return Optional.of(new DetectionResult(AtsType.LEVER, Confidence.MEDIUM, null));
        }
        if (lower.contains("ashbyhq.com") || lower.contains("ashby-job-posting")) {
            return Optional.of(new DetectionResult(AtsType.ASHBY, Confidence.MEDIUM, null));
        }
        if (lower.contains("myworkdayjobs.com") || lower.contains("workday")) {
            return Optional.of(new DetectionResult(AtsType.WORKDAY, Confidence.MEDIUM, null));
        }

        return Optional.empty();
    }
}
