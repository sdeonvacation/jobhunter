package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;

import java.util.Map;
import java.util.Optional;

public interface PosterExtractor {

    Optional<PosterInfo> extract(String rawHtml, Map<String, Object> rawJson);

    boolean supports(AtsType type);
}
