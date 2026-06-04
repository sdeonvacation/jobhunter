package dev.jobhub.filter;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Whitelist-based location filter. Only KEEP if location explicitly matches
 * Germany cities/country or generic remote. Everything else is SKIP.
 */
@Component
public class LocationFilterImpl implements LocationFilter {

    // Whitelist: Germany country + major cities
    private static final Pattern GERMANY_PATTERN = Pattern.compile(
            String.join("|",
                    "\\bgermany\\b", "\\bdeutschland\\b",
                    "\\bberlin\\b", "\\bmunich\\b", "\\bm[uü]nchen\\b",
                    "\\bhamburg\\b", "\\bfrankfurt\\b",
                    "\\bcologne\\b", "\\bk[oö]ln\\b",
                    "\\bstuttgart\\b", "\\bd[uü]sseldorf\\b",
                    "\\bdortmund\\b", "\\bdresden\\b", "\\bleipzig\\b",
                    "\\bnuremberg\\b", "\\bn[uü]rnberg\\b",
                    "\\bhannover\\b", "\\bbremen\\b", "\\bbonn\\b",
                    "\\bmannheim\\b", "\\bkarlsruhe\\b",
                    "\\bheidelberg\\b", "\\bpotsdam\\b", "\\bwalldorf\\b",
                    "\\bfreiburg\\b", "\\bessen\\b", "\\bduisburg\\b",
                    "\\bwiesbaden\\b", "\\bmainz\\b", "\\baachen\\b",
                    "\\bregensburg\\b", "\\baugsburg\\b", "\\brostock\\b",
                    "\\bjena\\b", "\\berkner\\b", "\\bbielefeld\\b"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // Generic remote (no country qualifier)
    private static final Pattern GENERIC_REMOTE_PATTERN = Pattern.compile(
            "^(remote|remote\\s*-\\s*(eu|europe|emea|global|worldwide|dach|ger(many)?))$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public FilterResult filter(String location) {
        if (location == null || location.isBlank()) {
            return FilterResult.skip("location: empty");
        }

        // Whitelist: Germany match anywhere in string
        if (GERMANY_PATTERN.matcher(location).find()) {
            return FilterResult.keep();
        }

        // Whitelist: purely "Remote" or "Remote - EU/Europe/EMEA/DACH"
        if (GENERIC_REMOTE_PATTERN.matcher(location.trim()).find()) {
            return FilterResult.keep();
        }

        // Everything else: SKIP
        return FilterResult.skip("location: not Germany");
    }
}
