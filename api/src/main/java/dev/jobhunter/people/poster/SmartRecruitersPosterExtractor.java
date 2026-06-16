package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts poster info from SmartRecruiters job pages.
 * SmartRecruiters pages may include a recruiter widget with name, title, and avatar.
 */
@Slf4j
@Component
public class SmartRecruitersPosterExtractor implements PosterExtractor {

    private static final Pattern RECRUITER_NAME_PATTERN =
            Pattern.compile("(?:class=[\"'][^\"']*recruiter[^\"']*[\"'][^>]*>\\s*(?:<[^>]+>)*\\s*)([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern RECRUITER_TITLE_PATTERN =
            Pattern.compile("(?:recruiter-title|job-poster-title|poster-role)[\"'][^>]*>\\s*([^<]+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern AVATAR_PATTERN =
            Pattern.compile("(?:recruiter|poster)[^>]*(?:src|background-image)[=:\\s\"']*(?:url\\()?[\"']?(https?://[^\"'\\s)]+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern LINKEDIN_URL_PATTERN =
            Pattern.compile("https?://(?:www\\.)?linkedin\\.com/in/[\\w-]+/?");

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.SMARTRECRUITERS;
    }

    @Override
    public Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson) {
        try {
            // Try JSON first (SmartRecruiters API may have creator info)
            if (rawJson != null) {
                PosterInfo info = extractFromJson(rawJson);
                if (info != null) {
                    return Optional.of(info);
                }
            }

            // Parse HTML for recruiter widget
            if (rawHtml != null && !rawHtml.isBlank()) {
                return extractFromHtml(rawHtml);
            }
        } catch (Exception e) {
            log.debug("SmartRecruiters poster extraction failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private PosterInfo extractFromJson(Map<String, Object> json) {
        for (String key : new String[]{"creator", "recruiter", "postingOwner"}) {
            Object val = json.get(key);
            if (val instanceof Map<?, ?> m) {
                String name = getStringOrNull(m, "name");
                if (name == null) {
                    String first = getStringOrNull(m, "firstName");
                    String last = getStringOrNull(m, "lastName");
                    if (first != null && last != null) {
                        name = first + " " + last;
                    }
                }
                if (name != null) {
                    String title = getStringOrNull(m, "title");
                    String avatarUrl = getStringOrNull(m, "avatarUrl");
                    return new PosterInfo(name, title, null, avatarUrl);
                }
            }
        }
        return null;
    }

    private Optional<PosterInfo> extractFromHtml(String html) {
        Matcher nameMatcher = RECRUITER_NAME_PATTERN.matcher(html);
        if (!nameMatcher.find()) {
            return Optional.empty();
        }

        String name = nameMatcher.group(1).trim();
        String title = null;
        String avatarUrl = null;
        String linkedinUrl = null;

        Matcher titleMatcher = RECRUITER_TITLE_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        Matcher avatarMatcher = AVATAR_PATTERN.matcher(html);
        if (avatarMatcher.find()) {
            avatarUrl = avatarMatcher.group(1);
        }

        Matcher linkedinMatcher = LINKEDIN_URL_PATTERN.matcher(html);
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
