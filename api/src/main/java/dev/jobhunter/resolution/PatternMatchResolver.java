package dev.jobhunter.resolution;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves career endpoint by checking known ATS URL patterns.
 * Instant, free, HIGH confidence when matched.
 * Patterns are ordered: specific patterns first, generic ones last.
 */
@Slf4j
@Component
public class PatternMatchResolver implements EndpointResolver {

    // LinkedHashMap preserves insertion order — specific patterns MUST come before generic ones
    private static final LinkedHashMap<Pattern, AtsType> PATTERNS = new LinkedHashMap<>();
    static {
        // Specific subdomain patterns first (extract slug from path)
        PATTERNS.put(Pattern.compile("https?://boards(?:-api)?\\.greenhouse\\.io/(?:v1/boards/)?([\\w-]+)/?.*"), AtsType.GREENHOUSE);
        PATTERNS.put(Pattern.compile("https?://jobs\\.eu\\.lever\\.co/([\\w-]+)/?.*"), AtsType.LEVER_EU);
        PATTERNS.put(Pattern.compile("https?://jobs\\.lever\\.co/([\\w-]+)/?.*"), AtsType.LEVER);
        PATTERNS.put(Pattern.compile("https?://jobs\\.ashbyhq\\.com/([\\w-]+)/?.*"), AtsType.ASHBY);
        PATTERNS.put(Pattern.compile("https?://([\\w-]+)\\.wd(\\d+)\\.myworkdayjobs\\.com/?.*"), AtsType.WORKDAY);
        PATTERNS.put(Pattern.compile("https?://wd(\\d+)\\.myworkdayjobs\\.com/([\\w-]+)/?.*"), AtsType.WORKDAY);
        // Generic greenhouse subdomain pattern last (extract slug from subdomain)
        PATTERNS.put(Pattern.compile("https?://(?!boards)([\\w-]+)\\.greenhouse\\.io/?.*"), AtsType.GREENHOUSE);
    }

    // Common URL patterns constructed from company name
    private static final List<String> URL_TEMPLATES = List.of(
            "https://%s.greenhouse.io",
            "https://boards.greenhouse.io/%s",
            "https://jobs.lever.co/%s",
            "https://jobs.eu.lever.co/%s",
            "https://jobs.ashbyhq.com/%s"
    );

    @Override
    public ResolutionResultDto resolve(String companyName, String domain) {
        List<ResolutionResultDto.CandidateUrl> candidates = new ArrayList<>();
        String normalizedSlug = companyName.toLowerCase().replaceAll("[^a-z0-9]", "");

        // Generate candidate URLs from known patterns
        for (String template : URL_TEMPLATES) {
            String candidateUrl = String.format(template, normalizedSlug);
            AtsType atsType = detectAtsFromUrl(candidateUrl);
            if (atsType != null) {
                candidates.add(new ResolutionResultDto.CandidateUrl(
                        candidateUrl, atsType, Confidence.LOW, "PATTERN_MATCH"
                ));
            }
        }

        // If a career URL hint or domain-based URL was provided, check it directly
        if (domain != null && !domain.isBlank()) {
            String careersUrl = "https://careers." + domain;
            candidates.add(new ResolutionResultDto.CandidateUrl(
                    careersUrl, AtsType.UNKNOWN, Confidence.LOW, "PATTERN_MATCH"
            ));
        }

        // No HIGH confidence from pattern generation alone (would need HTTP verification)
        if (candidates.isEmpty()) {
            return new ResolutionResultDto(
                    List.of(), null, Confidence.LOW, "PATTERN_MATCH", null, false
            );
        }

        return new ResolutionResultDto(
                candidates,
                null, // No selected URL without verification
                Confidence.LOW,
                "PATTERN_MATCH",
                null,
                false
        );
    }

    /**
     * Check if a given URL matches a known ATS pattern. Used by CompositeEndpointResolver
     * when a careerUrlHint is provided.
     */
    public ResolutionResultDto resolveFromUrl(String url) {
        for (Map.Entry<Pattern, AtsType> entry : PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(url);
            if (matcher.matches()) {
                var candidate = new ResolutionResultDto.CandidateUrl(
                        url, entry.getValue(), Confidence.HIGH, "PATTERN_MATCH"
                );
                return new ResolutionResultDto(
                        List.of(candidate),
                        url,
                        Confidence.HIGH,
                        "PATTERN_MATCH",
                        null,
                        false
                );
            }
        }
        return new ResolutionResultDto(List.of(), null, Confidence.LOW, "PATTERN_MATCH", null, false);
    }

    private AtsType detectAtsFromUrl(String url) {
        for (Map.Entry<Pattern, AtsType> entry : PATTERNS.entrySet()) {
            if (entry.getKey().matcher(url).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }
}
