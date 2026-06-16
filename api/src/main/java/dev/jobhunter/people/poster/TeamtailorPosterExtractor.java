package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts poster info from Teamtailor job pages.
 * Teamtailor pages typically show a recruiter section with photo, name, and title.
 */
@Slf4j
@Component
public class TeamtailorPosterExtractor implements PosterExtractor {

    // Teamtailor recruiter section: <div class="recruiter"> or data-controller="recruiter"
    private static final Pattern RECRUITER_SECTION_PATTERN =
            Pattern.compile("(?:class=[\"'][^\"']*recruiter[^\"']*[\"']|data-controller=[\"']recruiter[\"'])[^>]*>(.*?)</(?:div|section)>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern NAME_IN_SECTION_PATTERN =
            Pattern.compile("<(?:h[2-4]|span|p|strong)[^>]*>\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\s*</",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE_IN_SECTION_PATTERN =
            Pattern.compile("(?:title|role|position)[\"'][^>]*>\\s*([^<]+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern IMG_SRC_PATTERN =
            Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern LINKEDIN_URL_PATTERN =
            Pattern.compile("https?://(?:www\\.)?linkedin\\.com/in/[\\w-]+/?");

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.TEAMTAILOR;
    }

    @Override
    public Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson) {
        try {
            // Try JSON recruiter field
            if (rawJson != null) {
                PosterInfo info = extractFromJson(rawJson);
                if (info != null) {
                    return Optional.of(info);
                }
            }

            // Parse HTML recruiter section
            if (rawHtml != null && !rawHtml.isBlank()) {
                return extractFromHtml(rawHtml);
            }
        } catch (Exception e) {
            log.debug("Teamtailor poster extraction failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private PosterInfo extractFromJson(Map<String, Object> json) {
        Object recruiter = json.get("recruiter");
        if (recruiter instanceof Map<?, ?> m) {
            String name = getStringOrNull(m, "name");
            if (name != null) {
                String title = getStringOrNull(m, "title");
                String avatarUrl = getStringOrNull(m, "picture");
                if (avatarUrl == null) avatarUrl = getStringOrNull(m, "avatar");
                String linkedinUrl = getStringOrNull(m, "linkedin");
                return new PosterInfo(name, title, linkedinUrl, avatarUrl);
            }
        }
        return null;
    }

    private Optional<PosterInfo> extractFromHtml(String html) {
        // Try to find recruiter section first
        Matcher sectionMatcher = RECRUITER_SECTION_PATTERN.matcher(html);
        String searchArea = sectionMatcher.find() ? sectionMatcher.group(1) : html;

        Matcher nameMatcher = NAME_IN_SECTION_PATTERN.matcher(searchArea);
        if (!nameMatcher.find()) {
            return Optional.empty();
        }

        String name = nameMatcher.group(1).trim();
        String title = null;
        String avatarUrl = null;
        String linkedinUrl = null;

        Matcher titleMatcher = TITLE_IN_SECTION_PATTERN.matcher(searchArea);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        Matcher imgMatcher = IMG_SRC_PATTERN.matcher(searchArea);
        if (imgMatcher.find()) {
            avatarUrl = imgMatcher.group(1);
        }

        Matcher linkedinMatcher = LINKEDIN_URL_PATTERN.matcher(searchArea);
        if (linkedinMatcher.find()) {
            linkedinUrl = linkedinMatcher.group();
        }

        return Optional.of(new PosterInfo(name, title, linkedinUrl, avatarUrl));
    }

    private String getStringOrNull(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s && !s.isBlank() ? s : null;
    }
}
