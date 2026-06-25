package dev.jobhunter.filter;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Location filter backed by CityCountryResolver (GeoNames CSV + built-in country patterns).
 * Remote-EU patterns are checked first; everything else is resolved to ISO2 and tested
 * against the target-country set configured in CityCountryResolver.
 */
@Component
public class LocationFilterImpl implements LocationFilter {

    private final CityCountryResolver cityCountryResolver;
    private final Pattern remotePattern;
    private final String unknownAction; // "skip" or "keep"

    // Default remote patterns (used when config section is absent or empty)
    private static final List<String> DEFAULT_REMOTE_PATTERNS = List.of(
            "^(remote|remote\\s*-\\s*(eu|europe|emea|global|worldwide|dach|ger(many)?))$"
    );

    public LocationFilterImpl(PersonalProfileLoader profileLoader, CityCountryResolver cityCountryResolver) {
        this.cityCountryResolver = cityCountryResolver;

        PersonalProfile profile = profileLoader.getProfile();
        List<String> remotePatterns = new ArrayList<>(DEFAULT_REMOTE_PATTERNS);
        String action = "skip";

        if (profile != null && profile.filters() != null) {
            PersonalProfile.FilterConfig filters = profile.filters();

            if (filters.location() != null) {
                PersonalProfile.LocationFilterConfig locationConfig = filters.location();
                if (locationConfig.remotePatterns() != null && !locationConfig.remotePatterns().isEmpty()) {
                    remotePatterns = new ArrayList<>(locationConfig.remotePatterns());
                }
                if (locationConfig.unknownAction() != null) {
                    action = locationConfig.unknownAction();
                }
            }

            // Merge in visa-sponsorship remote-EU patterns (additional remote region matchers)
            if (filters.visaSponsorship() != null
                    && filters.visaSponsorship().remoteEuPatterns() != null
                    && !filters.visaSponsorship().remoteEuPatterns().isEmpty()) {
                remotePatterns.addAll(filters.visaSponsorship().remoteEuPatterns());
            }
        }

        this.remotePattern = Pattern.compile(String.join("|", remotePatterns), Pattern.CASE_INSENSITIVE);
        this.unknownAction = action;
    }

    @Override
    public LocationFilterResult filter(String location) {
        if (location == null || location.isBlank()) {
            return LocationFilterResult.skip("location: blank");
        }

        // 1. Remote-EU pattern check — runs before city lookup so "Remote - EU" doesn't need a city match
        if (remotePattern.matcher(location.trim()).find()) {
            return LocationFilterResult.keep("REMOTE_EU");
        }

        // 2. Resolve free-text location to ISO2 country code
        Optional<String> iso = cityCountryResolver.resolve(location);
        if (iso.isPresent()) {
            String code = iso.get();
            if (cityCountryResolver.isTargetCountry(code)) {
                return LocationFilterResult.keep(code);
            }
            return LocationFilterResult.skip("location: " + code + " not a target country");
        }

        // 3. Unknown location — apply configured policy
        if ("keep".equalsIgnoreCase(unknownAction)) {
            return LocationFilterResult.keep(null);
        }
        return LocationFilterResult.skip("location: not in target locations");
    }
}
