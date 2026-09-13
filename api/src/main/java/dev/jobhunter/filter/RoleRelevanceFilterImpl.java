package dev.jobhunter.filter;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RoleRelevanceFilterImpl implements RoleRelevanceFilter {

    private final Pattern engineeringPattern;
    private final Pattern excludedRolesPattern;

    /** Compiled override patterns, memoized per distinct override value. */
    private final Map<FilterOverrides, CompiledRolePatterns> overrideCache = new ConcurrentHashMap<>();

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

    @Override
    public FilterResult filter(String jobTitle, FilterOverrides overrides) {
        if (overrides == null || !overrides.hasRoleOverride()) {
            return filter(jobTitle);
        }
        if (jobTitle == null || jobTitle.isBlank()) {
            return FilterResult.keep();
        }

        CompiledRolePatterns compiled = overrideCache.computeIfAbsent(overrides, RoleRelevanceFilterImpl::compile);

        // An empty override exclude list means "no exclusions" (not "exclude nothing matches").
        if (compiled.exclude() != null && compiled.exclude().matcher(jobTitle).find()) {
            return FilterResult.skip("non-engineering role");
        }

        if (compiled.include().matcher(jobTitle).find()) {
            return FilterResult.keep();
        }

        return FilterResult.skip("non-engineering role");
    }

    private static CompiledRolePatterns compile(FilterOverrides overrides) {
        List<String> includePatterns = overrides.roleIncludePatterns() != null
                ? overrides.roleIncludePatterns() : List.of();
        List<String> excludeKeywords = overrides.roleExcludeKeywords() != null
                ? overrides.roleExcludeKeywords() : List.of();

        Pattern include = Pattern.compile(String.join("|", includePatterns), Pattern.CASE_INSENSITIVE);

        // Null pattern (rather than a match-all regex) so an empty exclude list drops nothing.
        Pattern exclude = excludeKeywords.isEmpty()
                ? null
                : Pattern.compile(
                        excludeKeywords.stream()
                                .map(kw -> kw.startsWith("\\b") ? kw : "\\b" + kw + "\\b")
                                .collect(Collectors.joining("|")),
                        Pattern.CASE_INSENSITIVE);

        return new CompiledRolePatterns(include, exclude);
    }

    private record CompiledRolePatterns(Pattern include, Pattern exclude) {
    }
}
