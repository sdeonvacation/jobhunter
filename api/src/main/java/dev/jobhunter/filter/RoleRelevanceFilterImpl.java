package dev.jobhunter.filter;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RoleRelevanceFilterImpl implements RoleRelevanceFilter {

    private final Pattern engineeringPattern;
    private final Pattern excludedRolesPattern;

    public RoleRelevanceFilterImpl(PersonalProfileLoader profileLoader) {
        PersonalProfile profile = profileLoader.getProfile();

        if (profile.filters() == null || profile.filters().role() == null) {
            throw new IllegalStateException("profile.yaml must define filters.role with include-patterns and exclude-keywords");
        }

        PersonalProfile.RoleFilterConfig roleConfig = profile.filters().role();
        List<String> includePatterns = roleConfig.includePatterns();
        List<String> excludeKeywords = roleConfig.excludeKeywords();

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
