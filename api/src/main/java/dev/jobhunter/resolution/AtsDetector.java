package dev.jobhunter.resolution;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects ATS type from URL using pattern matching and (optionally) HTML inspection.
 * Patterns are ordered: specific patterns first, generic ones last to avoid wrong slug extraction.
 */
@Component
public class AtsDetector {

    // LinkedHashMap preserves insertion order — specific patterns MUST come before generic ones
    private static final LinkedHashMap<Pattern, AtsType> URL_PATTERNS = new LinkedHashMap<>();
    static {
        // Specific subdomain patterns first (extract slug from path)
        URL_PATTERNS.put(Pattern.compile("https?://boards(?:-api)?\\.greenhouse\\.io/(?:v1/boards/)?([\\w-]+).*"), AtsType.GREENHOUSE);
        URL_PATTERNS.put(Pattern.compile("https?://jobs\\.eu\\.lever\\.co/([\\w-]+).*"), AtsType.LEVER_EU);
        URL_PATTERNS.put(Pattern.compile("https?://jobs\\.lever\\.co/([\\w-]+).*"), AtsType.LEVER);
        URL_PATTERNS.put(Pattern.compile("https?://jobs\\.ashbyhq\\.com/([\\w-]+).*"), AtsType.ASHBY);
        URL_PATTERNS.put(Pattern.compile("https?://[\\w-]+\\.wd\\d+\\.myworkdayjobs\\.com.*"), AtsType.WORKDAY);
        URL_PATTERNS.put(Pattern.compile("https?://www\\.stepstone\\.(de|at|nl|be)/.*"), AtsType.STEPSTONE);
        // Generic greenhouse subdomain pattern last (extract slug from subdomain)
        URL_PATTERNS.put(Pattern.compile("https?://(?!boards)([\\w-]+)\\.greenhouse\\.io.*"), AtsType.GREENHOUSE);
    }

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
