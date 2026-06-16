package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts poster info from Lever and Lever EU job pages.
 * Lever pages may contain "posted by" patterns or owner metadata.
 */
@Slf4j
@Component
public class LeverPosterExtractor implements PosterExtractor {

    private static final Pattern POSTED_BY_PATTERN =
            Pattern.compile("(?:posted\\s+by|hiring\\s+manager|owner)[:\\s]*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern LINKEDIN_URL_PATTERN =
            Pattern.compile("https?://(?:www\\.)?linkedin\\.com/in/[\\w-]+/?");

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("(?:title|role)[:\\s\"]*([A-Z][\\w\\s,]+?)(?:[\"<\\n])",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.LEVER || type == AtsType.LEVER_EU;
    }

    @Override
    public Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson) {
        try {
            // Try JSON owner field
            if (rawJson != null) {
                String name = extractOwnerFromJson(rawJson);
                if (name != null) {
                    return Optional.of(new PosterInfo(name, null, null, null));
                }
            }

            // Try HTML patterns
            if (rawHtml != null && !rawHtml.isBlank()) {
                return extractFromHtml(rawHtml);
            }
        } catch (Exception e) {
            log.debug("Lever poster extraction failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private String extractOwnerFromJson(Map<String, Object> json) {
        // Lever API may have "owner" or "creator" fields
        for (String key : new String[]{"owner", "creator", "hiringManager"}) {
            Object val = json.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
            if (val instanceof Map<?, ?> m) {
                Object name = m.get("name");
                if (name instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private Optional<PosterInfo> extractFromHtml(String html) {
        String linkedinUrl = null;
        Matcher linkedinMatcher = LINKEDIN_URL_PATTERN.matcher(html);
        if (linkedinMatcher.find()) {
            linkedinUrl = linkedinMatcher.group();
        }

        Matcher nameMatcher = POSTED_BY_PATTERN.matcher(html);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            String title = extractTitle(html);
            return Optional.of(new PosterInfo(name, title, linkedinUrl, null));
        }

        return Optional.empty();
    }

    private String extractTitle(String html) {
        Matcher m = TITLE_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }
}
