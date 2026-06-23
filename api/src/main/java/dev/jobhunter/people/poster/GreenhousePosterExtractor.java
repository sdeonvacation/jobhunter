package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts poster info from Greenhouse job pages.
 * Greenhouse JSON API may include hiring_team data; HTML may contain recruiter mentions.
 */
@Slf4j
@Component
public class GreenhousePosterExtractor implements PosterExtractor {

    private static final Pattern LINKEDIN_URL_PATTERN =
            Pattern.compile("https?://(?:www\\.)?linkedin\\.com/in/[\\w-]+/?");

    private static final Pattern NAME_NEAR_LINKEDIN_PATTERN =
            Pattern.compile("(?:posted\\s+by|recruiter|hiring\\s+manager|contact)[:\\s]+(?:(?:recruiter|hiring\\s+manager|posted\\s+by)[:\\s]+)?((?-i:[A-Z])[a-z]+(?:\\s+(?-i:[A-Z])[a-z]+){1,2})",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.GREENHOUSE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson) {
        try {
            // Try JSON hiring_team first
            if (rawJson != null && rawJson.containsKey("hiring_team")) {
                Object hiringTeam = rawJson.get("hiring_team");
                if (hiringTeam instanceof List<?> team && !team.isEmpty()) {
                    for (Object member : team) {
                        if (member instanceof Map<?, ?> m) {
                            String name = getStringOrNull(m, "name");
                            String title = getStringOrNull(m, "title");
                            String linkedinUrl = getStringOrNull(m, "linkedin_url");
                            if (name != null) {
                                return Optional.of(new PosterInfo(name, title, linkedinUrl, null));
                            }
                        }
                    }
                }
            }

            // Try JSON metadata array
            if (rawJson != null && rawJson.containsKey("metadata")) {
                Object metadata = rawJson.get("metadata");
                if (metadata instanceof List<?> metaList) {
                    for (Object item : metaList) {
                        if (item instanceof Map<?, ?> m) {
                            String metaName = getStringOrNull(m, "name");
                            if (metaName != null && metaName.toLowerCase().contains("hiring")) {
                                String value = getStringOrNull(m, "value");
                                if (value != null) {
                                    return Optional.of(new PosterInfo(value, null, null, null));
                                }
                            }
                        }
                    }
                }
            }

            // Fall back to HTML parsing
            if (rawHtml != null && !rawHtml.isBlank()) {
                return extractFromHtml(rawHtml);
            }
        } catch (Exception e) {
            log.debug("Greenhouse poster extraction failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<PosterInfo> extractFromHtml(String html) {
        String linkedinUrl = null;
        Matcher linkedinMatcher = LINKEDIN_URL_PATTERN.matcher(html);
        if (linkedinMatcher.find()) {
            linkedinUrl = linkedinMatcher.group();
        }

        Matcher nameMatcher = NAME_NEAR_LINKEDIN_PATTERN.matcher(html);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            return Optional.of(new PosterInfo(name, null, linkedinUrl, null));
        }

        return Optional.empty();
    }

    private String getStringOrNull(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s && !s.isBlank() ? s : null;
    }
}
