package dev.jobhub.filter;

import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Blocklist-based location filter. KEEP by default (permissive for unknown locations).
 * Explicitly blocks known non-target regions (US, India, UK, etc.).
 * Explicitly keeps Germany and generic remote.
 */
@Component
public class LocationFilterImpl implements LocationFilter {

    private final Pattern germanyPattern;
    private final Pattern genericRemotePattern;
    private final Pattern restrictedRemotePattern;
    private final Pattern usPattern;
    private final Pattern indiaPattern;
    private final Pattern ukPattern;
    private final Pattern nonTargetPattern;
    private final Pattern multiLocationPattern;
    private final boolean useWhitelistMode;

    private static final List<String> DEFAULT_GERMANY_CITIES = List.of(
            "germany", "deutschland", "berlin", "munich", "m[uü]nchen",
            "hamburg", "frankfurt", "cologne", "k[oö]ln", "stuttgart",
            "d[uü]sseldorf", "dortmund", "dresden", "leipzig",
            "nuremberg", "n[uü]rnberg", "hannover", "bremen", "bonn",
            "mannheim", "karlsruhe", "heidelberg", "potsdam", "walldorf",
            "freiburg", "essen", "duisburg", "wiesbaden", "mainz", "aachen",
            "regensburg", "augsburg", "rostock", "jena", "erkner", "bielefeld"
    );

    private static final List<String> DEFAULT_REMOTE_PATTERNS = List.of(
            "\\bremote\\b", "\\bflexible\\b", "\\banywhere\\b",
            "\\bemea\\b", "\\beurope\\b", "\\beu\\b"
    );

    private static final List<String> RESTRICTED_REMOTE_REGIONS = List.of(
            "us", "usa", "united\\s+states", "india", "apac",
            "canada", "americas", "australia", "latam", "asia"
    );

    private static final List<String> US_LOCATIONS = List.of(
            "united\\s+states", "san\\s+jose", "san\\s+francisco",
            "new\\s+york", "austin", "seattle", "mountain\\s+view",
            "palo\\s+alto", "sunnyvale", "cupertino", "boston",
            "denver", "atlanta", "redmond", "bellevue", "chicago",
            "los\\s+angeles", "portland", "pittsburgh", "raleigh",
            "santa\\s+clara", "houston", "dallas", "phoenix",
            "philadelphia", "san\\s+diego", "san\\s+antonio"
    );

    private static final List<String> INDIA_LOCATIONS = List.of(
            "india", "bengaluru", "bangalore", "hyderabad", "pune",
            "noida", "mumbai", "gurgaon", "gurugram", "chennai",
            "kolkata", "delhi", "new\\s+delhi"
    );

    private static final List<String> UK_LOCATIONS = List.of(
            "united\\s+kingdom", "\\buk\\b", "london", "manchester",
            "edinburgh", "birmingham", "bristol", "cambridge",
            "oxford", "leeds", "glasgow", "liverpool"
    );

    private static final List<String> NON_TARGET_LOCATIONS = List.of(
            "netherlands", "nederland", "amsterdam", "rotterdam",
            "the\\s+hague", "den\\s+haag", "utrecht", "eindhoven", "delft",
            "israel", "tel\\s+aviv",
            "china", "shanghai", "beijing", "shenzhen",
            "japan", "tokyo", "osaka",
            "singapore",
            "sydney", "melbourne",
            "seoul",
            "taipei",
            "s[aã]o\\s+paulo",
            "mexico\\s+city"
    );

    public LocationFilterImpl(PersonalProfileLoader profileLoader) {
        PersonalProfile profile = profileLoader.getProfile();
        List<String> cities = DEFAULT_GERMANY_CITIES;
        List<String> remotePatterns = DEFAULT_REMOTE_PATTERNS;
        boolean customCities = false;

        if (profile.filters() != null && profile.filters().location() != null) {
            PersonalProfile.LocationFilterConfig locationConfig = profile.filters().location();
            if (!locationConfig.germanyCities().isEmpty()) {
                cities = locationConfig.germanyCities();
                customCities = true;
            }
            if (!locationConfig.remotePatterns().isEmpty()) {
                remotePatterns = locationConfig.remotePatterns();
            }
        }
        this.useWhitelistMode = customCities;

        // Germany pattern with word boundaries
        String cityRegex = cities.stream()
                .map(city -> city.startsWith("\\b") ? city : "\\b" + city + "\\b")
                .collect(Collectors.joining("|"));
        this.germanyPattern = Pattern.compile(cityRegex, Pattern.CASE_INSENSITIVE);

        // Generic remote pattern
        this.genericRemotePattern = Pattern.compile(
                String.join("|", remotePatterns),
                Pattern.CASE_INSENSITIVE
        );

        // Restricted remote: "Remote - US", "Remote-India", etc.
        String restrictedRegex = RESTRICTED_REMOTE_REGIONS.stream()
                .map(r -> "remote\\s*[-–]\\s*" + r)
                .collect(Collectors.joining("|"));
        this.restrictedRemotePattern = Pattern.compile(restrictedRegex, Pattern.CASE_INSENSITIVE);

        // US pattern
        String usRegex = US_LOCATIONS.stream()
                .map(loc -> "\\b" + loc + "\\b")
                .collect(Collectors.joining("|"));
        // Also match state abbreviations like ", CA" or ", NY"
        usRegex += "|,\\s*[A-Z]{2}\\b";
        this.usPattern = Pattern.compile(usRegex, Pattern.CASE_INSENSITIVE);

        // India pattern
        String indiaRegex = INDIA_LOCATIONS.stream()
                .map(loc -> "\\b" + loc + "\\b")
                .collect(Collectors.joining("|"));
        this.indiaPattern = Pattern.compile(indiaRegex, Pattern.CASE_INSENSITIVE);

        // UK pattern
        String ukRegex = UK_LOCATIONS.stream()
                .map(loc -> loc.startsWith("\\b") ? loc : "\\b" + loc + "\\b")
                .collect(Collectors.joining("|"));
        this.ukPattern = Pattern.compile(ukRegex, Pattern.CASE_INSENSITIVE);

        // Non-target countries pattern
        String nonTargetRegex = NON_TARGET_LOCATIONS.stream()
                .map(loc -> "\\b" + loc + "\\b")
                .collect(Collectors.joining("|"));
        this.nonTargetPattern = Pattern.compile(nonTargetRegex, Pattern.CASE_INSENSITIVE);

        // Multi-location pattern: "2 Locations", "12 locations"
        this.multiLocationPattern = Pattern.compile("\\d+\\s+locations?", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public FilterResult filter(String location) {
        if (location == null || location.isBlank()) {
            return FilterResult.keep();
        }

        String loc = location.trim();

        // Target: Germany match takes precedence
        if (germanyPattern.matcher(loc).find()) {
            return FilterResult.keep();
        }

        // Restricted remote (must check BEFORE generic remote)
        if (restrictedRemotePattern.matcher(loc).find()) {
            return FilterResult.skip("location: restricted remote");
        }

        // Generic remote / flexible / EMEA / Europe
        if (genericRemotePattern.matcher(loc).find()) {
            return FilterResult.keep();
        }

        // Blocklist: US
        if (usPattern.matcher(loc).find()) {
            return FilterResult.skip("location: US");
        }

        // Blocklist: India
        if (indiaPattern.matcher(loc).find()) {
            return FilterResult.skip("location: India");
        }

        // Blocklist: UK
        if (ukPattern.matcher(loc).find()) {
            return FilterResult.skip("location: UK");
        }

        // Blocklist: other non-target countries
        if (nonTargetPattern.matcher(loc).find()) {
            return FilterResult.skip("location: non-target country");
        }

        // Multi-location (e.g. "3 Locations")
        if (multiLocationPattern.matcher(loc).find()) {
            return FilterResult.skip("location: multi-location");
        }

        // Default: KEEP in blocklist mode, SKIP in whitelist mode
        return useWhitelistMode ? FilterResult.skip("location: not in configured cities") : FilterResult.keep();
    }
}
