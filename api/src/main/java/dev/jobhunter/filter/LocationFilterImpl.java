package dev.jobhunter.filter;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whitelist-based location filter. Only KEEP if location explicitly matches
 * configured target cities or whitelisted remote patterns. Everything else is SKIP.
 */
@Component
public class LocationFilterImpl implements LocationFilter {

    private final Pattern targetCitiesPattern;
    private final Pattern genericRemotePattern;

    // Default target city patterns (used when config section is absent)
    private static final List<String> DEFAULT_TARGET_CITIES = List.of(
            "germany", "deutschland", "berlin", "munich", "m[uü]nchen",
            "hamburg", "frankfurt", "cologne", "k[oö]ln", "stuttgart",
            "d[uü]sseldorf", "dortmund", "dresden", "leipzig",
            "nuremberg", "n[uü]rnberg", "hannover", "bremen", "bonn",
            "mannheim", "karlsruhe", "heidelberg", "potsdam", "walldorf",
            "freiburg", "essen", "duisburg", "wiesbaden", "mainz", "aachen",
            "regensburg", "augsburg", "rostock", "jena", "erkner", "bielefeld"
    );

    // Default remote patterns (used when config section is absent)
    private static final List<String> DEFAULT_REMOTE_PATTERNS = List.of(
            "^(remote|remote\\s*-\\s*(eu|europe|emea|global|worldwide|dach|ger(many)?))$"
    );

    public LocationFilterImpl(PersonalProfileLoader profileLoader) {
        PersonalProfile profile = profileLoader.getProfile();
        List<String> cities = DEFAULT_TARGET_CITIES;
        List<String> remotePatterns = DEFAULT_REMOTE_PATTERNS;

        if (profile.filters() != null && profile.filters().location() != null) {
            PersonalProfile.LocationFilterConfig locationConfig = profile.filters().location();
            if (!locationConfig.targetCities().isEmpty()) {
                cities = locationConfig.targetCities();
            }
            if (!locationConfig.remotePatterns().isEmpty()) {
                remotePatterns = locationConfig.remotePatterns();
            }
        }

        // Wrap city names with word boundaries
        String cityRegex = cities.stream()
                .map(city -> city.startsWith("\\b") ? city : "\\b" + city + "\\b")
                .collect(Collectors.joining("|"));
        this.targetCitiesPattern = Pattern.compile(cityRegex, Pattern.CASE_INSENSITIVE);

        // Remote patterns are used as-is (full regex)
        this.genericRemotePattern = Pattern.compile(
                String.join("|", remotePatterns),
                Pattern.CASE_INSENSITIVE
        );
    }

    @Override
    public FilterResult filter(String location) {
        if (location == null || location.isBlank()) {
            return FilterResult.skip("location: empty");
        }

        // Whitelist: target city match anywhere in string
        if (targetCitiesPattern.matcher(location).find()) {
            return FilterResult.keep();
        }

        // Whitelist: purely "Remote" or whitelisted remote region patterns
        if (genericRemotePattern.matcher(location.trim()).find()) {
            return FilterResult.keep();
        }

        // Everything else: SKIP
        return FilterResult.skip("location: not Germany");
    }
}
