package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Extracts poster info from Ashby job pages.
 * Ashby JSON responses may include hiring team or recruiter info.
 */
@Slf4j
@Component
public class AshbyPosterExtractor implements PosterExtractor {

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.ASHBY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson) {
        try {
            if (rawJson == null) {
                return Optional.empty();
            }

            // Ashby API: check for hiringTeam field
            PosterInfo info = extractFromHiringTeam(rawJson);
            if (info != null) {
                return Optional.of(info);
            }

            // Check for recruiter field
            info = extractFromRecruiter(rawJson);
            if (info != null) {
                return Optional.of(info);
            }

            // Check nested jobPosting.hiringTeam
            Object jobPosting = rawJson.get("jobPosting");
            if (jobPosting instanceof Map<?, ?> jp) {
                info = extractFromHiringTeam((Map<String, Object>) jp);
                if (info != null) {
                    return Optional.of(info);
                }
            }
        } catch (Exception e) {
            log.debug("Ashby poster extraction failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private PosterInfo extractFromHiringTeam(Map<String, Object> json) {
        Object hiringTeam = json.get("hiringTeam");
        if (hiringTeam instanceof java.util.List<?> team && !team.isEmpty()) {
            for (Object member : team) {
                if (member instanceof Map<?, ?> m) {
                    String name = getStringOrNull(m, "name");
                    String title = getStringOrNull(m, "title");
                    String linkedinUrl = getStringOrNull(m, "linkedinUrl");
                    String photoUrl = getStringOrNull(m, "photoUrl");
                    if (name != null) {
                        return new PosterInfo(name, title, linkedinUrl, photoUrl);
                    }
                }
            }
        }
        return null;
    }

    private PosterInfo extractFromRecruiter(Map<String, Object> json) {
        for (String key : new String[]{"recruiter", "hiringManager", "owner"}) {
            Object val = json.get(key);
            if (val instanceof Map<?, ?> m) {
                String name = getStringOrNull(m, "name");
                if (name != null) {
                    String title = getStringOrNull(m, "title");
                    String linkedinUrl = getStringOrNull(m, "linkedinUrl");
                    String photoUrl = getStringOrNull(m, "photoUrl");
                    return new PosterInfo(name, title, linkedinUrl, photoUrl);
                }
            }
        }
        return null;
    }

    private String getStringOrNull(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s && !s.isBlank() ? s : null;
    }
}
