package dev.jobhub.filter;

import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RoleRelevanceFilterImpl implements RoleRelevanceFilter {

    private final Pattern engineeringPattern;
    private final Pattern excludedRolesPattern;

    // Default include patterns (used when config section is absent)
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "engineer", "developer", "programmer", "\\bsre\\b", "devops",
            "dev\\s*ops", "software", "backend", "back[\\s-]end", "fullstack",
            "full[\\s-]stack", "frontend", "front[\\s-]end", "platform",
            "infrastructure", "\\bcloud\\b", "\\bml\\b", "machine\\s+learning",
            "\\bsde\\b", "\\bcto\\b", "\\bsdet\\b", "site\\s+reliability",
            "\\bdevsecops\\b", "\\bsys\\s*admin\\b", "\\bkubernetes\\b",
            "\\bk8s\\b", "\\blead\\b", "\\bqa\\b"
    );

    // Default exclude keywords (used when config section is absent)
    private static final List<String> DEFAULT_EXCLUDE_KEYWORDS = List.of(
            "manager", "architect", "analyst", "director", "principal",
            "counsel", "legal", "recruiter", "designer", "marketing",
            "finance", "accountant", "hr"
    );

    public RoleRelevanceFilterImpl(PersonalProfileLoader profileLoader) {
        PersonalProfile profile = profileLoader.getProfile();
        List<String> includePatterns = DEFAULT_INCLUDE_PATTERNS;
        List<String> excludeKeywords = DEFAULT_EXCLUDE_KEYWORDS;

        if (profile.filters() != null && profile.filters().role() != null) {
            PersonalProfile.RoleFilterConfig roleConfig = profile.filters().role();
            if (!roleConfig.includePatterns().isEmpty()) {
                includePatterns = roleConfig.includePatterns();
            }
            if (!roleConfig.excludeKeywords().isEmpty()) {
                excludeKeywords = roleConfig.excludeKeywords();
            }
        }

        this.engineeringPattern = Pattern.compile(
                String.join("|", includePatterns),
                Pattern.CASE_INSENSITIVE
        );

        // Wrap exclude keywords with word boundaries
        String excludeRegex = excludeKeywords.stream()
                .map(kw -> kw.startsWith("\\b") ? kw : "\\b" + kw + "\\b")
                .collect(Collectors.joining("|"));
        this.excludedRolesPattern = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE);
    }

    @Override
    public FilterResult filter(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return FilterResult.keep();
        }

        // Exclusions take priority
        if (excludedRolesPattern.matcher(jobTitle).find()) {
            return FilterResult.skip("non-engineering role");
        }

        if (engineeringPattern.matcher(jobTitle).find()) {
            return FilterResult.keep();
        }

        return FilterResult.skip("non-engineering role");
    }
}
