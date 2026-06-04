package dev.jobhub.filter;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class LocationFilterImpl implements LocationFilter {

    // Germany: country name + major cities
    private static final Pattern GERMANY_PATTERN = Pattern.compile(
            String.join("|",
                    "germany", "deutschland",
                    "berlin", "munich", "m[uü]nchen", "hamburg", "frankfurt",
                    "cologne", "k[oö]ln", "stuttgart", "d[uü]sseldorf",
                    "dortmund", "dresden", "leipzig", "nuremberg", "n[uü]rnberg",
                    "hannover", "bremen", "bonn", "mannheim", "karlsruhe",
                    "heidelberg", "potsdam", "walldorf"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // Netherlands: country name + major cities
    private static final Pattern NETHERLANDS_PATTERN = Pattern.compile(
            String.join("|",
                    "netherlands", "nederland",
                    "amsterdam", "rotterdam", "the\\s+hague", "den\\s+haag",
                    "utrecht", "eindhoven", "delft"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // Broad EU/EMEA signals
    private static final Pattern EMEA_EUROPE_PATTERN = Pattern.compile(
            "\\b(emea|europe|\\beu\\b)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Multi-location pattern: "2 Locations", "3 Locations", etc.
    private static final Pattern MULTI_LOCATION_PATTERN = Pattern.compile(
            "\\d+\\s+locations?",
            Pattern.CASE_INSENSITIVE
    );

    // Generic remote/flexible (no country qualifier)
    private static final Pattern GENERIC_REMOTE_PATTERN = Pattern.compile(
            "\\b(remote|anywhere|flex(ible)?|hybrid)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Remote restricted to non-target regions: "Remote - US", "Remote - India", "Remote - APAC"
    private static final Pattern RESTRICTED_REMOTE_PATTERN = Pattern.compile(
            "remote\\s*[-–—]\\s*(us|usa|united\\s+states|india|apac|asia|latam|canada|americas|australia)",
            Pattern.CASE_INSENSITIVE
    );

    // US indicators
    private static final Pattern US_PATTERN = Pattern.compile(
            String.join("|",
                    "\\busa\\b", "\\bus\\b", "united\\s+states",
                    "\\bcalifornia\\b", "\\b(ca|ny|tx|wa|ma|il|co|ga|nc|va|pa|az|or|fl|oh|nj|md)\\b",
                    "san\\s+francisco", "san\\s+jose", "santa\\s+clara", "mountain\\s+view",
                    "palo\\s+alto", "sunnyvale", "cupertino", "seattle", "new\\s+york",
                    "austin", "chicago", "boston", "denver", "atlanta", "raleigh",
                    "portland", "pittsburgh", "los\\s+angeles", "san\\s+diego",
                    "washington\\s*,?\\s*d\\.?c\\.?", "redmond", "bellevue"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // India indicators
    private static final Pattern INDIA_PATTERN = Pattern.compile(
            String.join("|",
                    "\\bindia\\b", "bengaluru", "bangalore", "hyderabad",
                    "pune", "noida", "mumbai", "gurgaon", "gurugram", "chennai"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // Other non-target countries
    private static final Pattern OTHER_NON_TARGET_PATTERN = Pattern.compile(
            String.join("|",
                    "\\bisrael\\b", "tel\\s+aviv",
                    "\\bchina\\b", "shanghai", "beijing", "shenzhen",
                    "\\bjapan\\b", "tokyo",
                    "\\btaiwan\\b", "taipei",
                    "\\bkorea\\b", "seoul",
                    "\\bsingapore\\b",
                    "\\baustralia\\b", "sydney", "melbourne",
                    "\\bbrazil\\b", "s[aã]o\\s+paulo",
                    "\\bmexico\\b", "mexico\\s+city",
                    "\\bitaly\\b", "\\bmilan\\b", "\\brome\\b",
                    "\\bfrance\\b", "\\bparis\\b",
                    "\\bspain\\b", "\\bmadrid\\b", "\\bbarcelona\\b",
                    "\\bportugal\\b", "\\blisbon\\b",
                    "\\bpoland\\b", "\\bwarsaw\\b", "\\bkrakow\\b",
                    "\\bczech\\b", "\\bprague\\b",
                    "\\bhungary\\b", "\\bbudapest\\b",
                    "\\bromania\\b", "\\bbucharest\\b",
                    "\\baustria\\b", "\\bvienna\\b",
                    "\\bswitzerland\\b", "\\bzurich\\b", "\\bgeneva\\b",
                    "\\bireland\\b", "\\bdublin\\b", "\\bcork\\b",
                    "\\bcanada\\b", "\\btoronto\\b", "\\bvancouver\\b", "\\bmontreal\\b"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // UK-only indicators (skip unless also has remote/EMEA)
    private static final Pattern UK_PATTERN = Pattern.compile(
            String.join("|",
                    "\\blondon\\b", "\\bmanchester\\b", "\\bedinburgh\\b",
                    "\\bbirmingham\\b", "\\bleeds\\b", "\\bbristol\\b",
                    "\\bcambridge\\b", "\\boxford\\b",
                    "\\bunited\\s+kingdom\\b", "\\buk\\b"
            ),
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public FilterResult filter(String location) {
        if (location == null || location.isBlank()) {
            return FilterResult.keep();
        }

        // Ambiguous-only locations (no actual city/country)
        if (location.matches("(?i)^(hybrid|in-office|distributed|hybrid;\\s*in-office|on-?site)$")) {
            return FilterResult.skip("location: ambiguous");
        }

        // Positive matches first (target locations)
        if (GERMANY_PATTERN.matcher(location).find()) {
            return FilterResult.keep();
        }
        if (EMEA_EUROPE_PATTERN.matcher(location).find()) {
            return FilterResult.keep();
        }
        if (MULTI_LOCATION_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: ambiguous multi-location");
        }

        // Netherlands is explicitly excluded
        if (NETHERLANDS_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: Netherlands");
        }

        // Negative matches BEFORE generic remote (so "San Francisco or Remote" → SKIP)
        if (US_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: US");
        }
        if (INDIA_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: India");
        }
        if (OTHER_NON_TARGET_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: non-target country");
        }
        if (UK_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: UK");
        }

        // Remote handling (only after non-target checks)
        if (RESTRICTED_REMOTE_PATTERN.matcher(location).find()) {
            return FilterResult.skip("location: restricted remote");
        }
        if (GENERIC_REMOTE_PATTERN.matcher(location).find()) {
            return FilterResult.keep();
        }

        // Default: permissive (benefit of the doubt)
        return FilterResult.keep();
    }
}
