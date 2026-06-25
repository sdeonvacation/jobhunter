package dev.jobhunter.filter.geo;

import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.Locale;
/**
 * Resolves a free-text location string to an ISO2 country code.
 *
 * Resolution order:
 *  1. Profile-configured DE patterns (highest priority)
 *  2. Built-in country name/code patterns (Germany, Netherlands, "NL", etc.)
 *  3. City lookup from city-country.csv (GeoNames cities5000 subset)
 *
 * When a city name maps to multiple countries, target countries are preferred.
 */
@Slf4j
@Component
public class CityCountryResolver {

    // Built-in country name → ISO2 (covers explicit country names in job postings)
    private static final Map<String, String> COUNTRY_NAME_TO_ISO = Map.ofEntries(
        Map.entry("germany", "DE"), Map.entry("deutschland", "DE"),
        Map.entry("netherlands", "NL"), Map.entry("nederland", "NL"), Map.entry("the netherlands", "NL"),
        Map.entry("austria", "AT"), Map.entry("österreich", "AT"), Map.entry("oesterreich", "AT"),
        Map.entry("switzerland", "CH"), Map.entry("schweiz", "CH"), Map.entry("suisse", "CH"),
        Map.entry("ireland", "IE"), Map.entry("éire", "IE"),
        Map.entry("sweden", "SE"), Map.entry("sverige", "SE"),
        Map.entry("denmark", "DK"), Map.entry("danmark", "DK"),
        Map.entry("finland", "FI"), Map.entry("suomi", "FI"),
        Map.entry("spain", "ES"), Map.entry("españa", "ES"), Map.entry("espana", "ES"),
        Map.entry("united kingdom", "GB"), Map.entry("england", "GB"),
        Map.entry("united states", "US"), Map.entry("usa", "US"), Map.entry("u.s.a.", "US"),
        Map.entry("india", "IN"), Map.entry("china", "CN"), Map.entry("canada", "CA"),
        Map.entry("australia", "AU"), Map.entry("singapore", "SG"), Map.entry("japan", "JP"),
        Map.entry("france", "FR"), Map.entry("italy", "IT"), Map.entry("poland", "PL"),
        Map.entry("portugal", "PT"), Map.entry("romania", "RO"), Map.entry("czechia", "CZ"),
        Map.entry("czech republic", "CZ"), Map.entry("hungary", "HU"), Map.entry("belgium", "BE"),
        Map.entry("norway", "NO"), Map.entry("brasil", "BR"), Map.entry("brazil", "BR"),
        Map.entry("mexico", "MX"), Map.entry("argentina", "AR"), Map.entry("ukraine", "UA"),
        Map.entry("israel", "IL"), Map.entry("turkey", "TR"), Map.entry("türkiye", "TR")
    );

    // ISO2 2-letter code patterns (word-boundary matched); ordered longest-match first where ambiguous
    private static final Map<String, String> ISO_CODE_TO_ISO = Map.ofEntries(
        Map.entry("nl", "NL"), Map.entry("de", "DE"), Map.entry("at", "AT"),
        Map.entry("ch", "CH"), Map.entry("ie", "IE"), Map.entry("se", "SE"),
        Map.entry("dk", "DK"), Map.entry("fi", "FI"), Map.entry("es", "ES"),
        Map.entry("gb", "GB"), Map.entry("uk", "GB"), Map.entry("us", "US"),
        Map.entry("in", "IN"), Map.entry("cn", "CN"), Map.entry("ca", "CA"),
        Map.entry("au", "AU"), Map.entry("sg", "SG"), Map.entry("jp", "JP"),
        Map.entry("fr", "FR"), Map.entry("it", "IT"), Map.entry("pl", "PL"),
        Map.entry("pt", "PT"), Map.entry("ro", "RO"), Map.entry("cz", "CZ"),
        Map.entry("hu", "HU"), Map.entry("be", "BE"), Map.entry("no", "NO"),
        Map.entry("br", "BR"), Map.entry("mx", "MX"), Map.entry("ua", "UA"),
        Map.entry("il", "IL"), Map.entry("tr", "TR")
    );

    // Compiled once at init; built from COUNTRY_NAME_TO_ISO entries (longest key first avoids partial matches)
    private List<Map.Entry<Pattern, String>> countryNamePatterns = new ArrayList<>();

    // lowercase city → ordered list of ISO2 codes (first entry = GeoNames primary)
    private Map<String, List<String>> cityToIsos = new HashMap<>();

    private Set<String> targetIsos = new HashSet<>();
    private List<Pattern> profileDePatterns = new ArrayList<>();

    private final PersonalProfileLoader profileLoader;

    public CityCountryResolver(PersonalProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    @PostConstruct
    void init() {
        compileBuiltinPatterns();
        loadCsv();
        loadProfileConfig();
        log.info("CityCountryResolver loaded: {} cities, {} target countries", cityToIsos.size(), targetIsos.size());
    }

    private void compileBuiltinPatterns() {
        // Sort by key length descending so "the netherlands" matches before "netherlands"
        COUNTRY_NAME_TO_ISO.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .forEach(e -> countryNamePatterns.add(Map.entry(
                Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b", Pattern.CASE_INSENSITIVE),
                e.getValue()
            )));
    }

    private void loadCsv() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("geo/city-country.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int comma = line.lastIndexOf(',');
                if (comma < 1) continue;
                String city = line.substring(0, comma).trim();
                String iso = line.substring(comma + 1).trim().toUpperCase();
                if (city.isEmpty() || iso.isEmpty()) continue;
                cityToIsos.computeIfAbsent(city, k -> new ArrayList<>()).add(iso);
            }
        } catch (Exception e) {
            log.error("Failed to load city-country.csv: {}", e.getMessage(), e);
        }
    }

    private void loadProfileConfig() {
        try {
            PersonalProfile profile = profileLoader.getProfile();
            if (profile != null && profile.filters() != null && profile.filters().visaSponsorship() != null) {
                var visa = profile.filters().visaSponsorship();

                if (visa.targetCountries() != null) {
                    for (String pattern : visa.targetCountries()) {
                        // Strip regex metacharacters to get plain text for lookup
                        String clean = pattern.replaceAll("\\\\b", "").replaceAll("\\\\B", "")
                                              .replaceAll("[\\^$.*+?{}\\[\\]|()]", "").toLowerCase().trim();
                        String iso = COUNTRY_NAME_TO_ISO.get(clean);
                        if (iso != null) targetIsos.add(iso);
                        String iso2 = ISO_CODE_TO_ISO.get(clean);
                        if (iso2 != null) targetIsos.add(iso2);
                    }
                }

                // DE is always a target (visa-exempt home country)
                targetIsos.add("DE");

                if (visa.dePatterns() != null) {
                    for (String p : visa.dePatterns()) {
                        profileDePatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                    }
                }
            } else {
                applyDefaultTargets();
            }
        } catch (Exception e) {
            log.warn("CityCountryResolver: could not read profile config, using defaults: {}", e.getMessage());
            applyDefaultTargets();
        }
    }

    private void applyDefaultTargets() {
        targetIsos.addAll(Set.of("DE", "NL", "AT", "CH", "IE", "SE", "DK", "FI", "ES"));
    }

    /**
     * Resolve a location string to an ISO2 country code.
     *
     * @return Optional with ISO2 (e.g. "NL", "DE") or empty if unresolvable
     */
    public Optional<String> resolve(String location) {
        if (location == null || location.isBlank()) return Optional.empty();
        String lower = location.toLowerCase();

        // 1. Profile-configured DE patterns (highest priority — visa-exempt country)
        for (Pattern p : profileDePatterns) {
            if (p.matcher(lower).find()) return Optional.of("DE");
        }

        // 2. Built-in country name lookup (longest match first)
        for (Map.Entry<Pattern, String> entry : countryNamePatterns) {
            if (entry.getKey().matcher(lower).find()) return Optional.of(entry.getValue());
        }

        // 3. Standalone 2-letter location (entire string is a country code, case-insensitive).
        //    Handles bare "NL", "nl", "DE" without the city-lookup overhead.
        String trimmedLoc = location.trim();
        if (trimmedLoc.length() == 2) {
            String iso = ISO_CODE_TO_ISO.get(trimmedLoc.toLowerCase(Locale.ROOT));
            if (iso != null) return Optional.of(iso);
        }

        // 4. ISO uppercase token in compound strings (e.g. "Amsterdam, NL", "Veghel, NL").
        //    Runs BEFORE city lookup — an explicit country code is stronger evidence than a city name
        //    that might be shared across countries (e.g. "Los Angeles" exists in both US and ES).
        //    Lowercase tokens are excluded: "in", "it", "be", "no" are common English words.
        for (String segment : location.split("[,\n\\s]+")) {
            String token = segment.trim();
            if (token.length() == 2
                    && token.equals(token.toUpperCase(Locale.ROOT))
                    && !token.equals(token.toLowerCase(Locale.ROOT))) {
                String iso = ISO_CODE_TO_ISO.get(token.toLowerCase(Locale.ROOT));
                if (iso != null) return Optional.of(iso);
            }
        }

        // 5. City lookup — split by comma/newline, try each segment
        String[] segments = location.split("[,\n]");
        for (String segment : segments) {
            Optional<String> r = resolveSegment(segment.trim());
            if (r.isPresent()) return r;
        }

        return Optional.empty();
    }

    private Optional<String> resolveSegment(String segment) {
        if (segment == null || segment.isBlank()) return Optional.empty();
        String lower = segment.toLowerCase().trim();

        // Try full segment as city name
        List<String> isos = cityToIsos.getOrDefault(lower, List.of());
        if (!isos.isEmpty()) return Optional.of(pickBestIso(isos));

        // Try dash-separated parts (handles "Hybrid - Berlin", "Remote - Amsterdam").
        if (lower.contains("-")) {
            for (String part : lower.split("\\s*-\\s*")) {
                part = part.trim();
                if (part.length() < 3) continue;
                List<String> partIsos = cityToIsos.getOrDefault(part, List.of());
                if (!partIsos.isEmpty()) return Optional.of(pickBestIso(partIsos));
            }
        }

        // Try leading word only (handles "Frankfurt am Main" → "frankfurt").
        // Only first word is tried — prevents "New York City" → "york" → York, GB.
        String[] words = lower.split("\\s+");
        if (words.length > 1 && words[0].length() >= 3) {
            List<String> wordIsos = cityToIsos.getOrDefault(words[0], List.of());
            if (!wordIsos.isEmpty()) return Optional.of(pickBestIso(wordIsos));
        }

        return Optional.empty();
    }

    // Fallback disambiguation order when no target country matched.
    // Reflects rough city-name prominence in European job postings.
    private static final List<String> DISAMBIGUATION_PRIORITY = List.of(
            "GB", "FR", "DE", "NL", "IT", "ES", "AT", "CH", "BE", "SE",
            "NO", "DK", "FI", "IE", "PL", "PT", "CZ", "HU", "RO", "UA",
            "US", "CA", "AU", "IN", "CN", "JP", "SG", "BR", "MX"
    );

    /**
     * When a city exists in multiple countries:
     * - If exactly one target country matches → return it.
     * - If multiple target countries match → prefer non-visa-exempt (conservative:
     *   run visa check rather than silently skip it for a border-town ambiguity).
     * - If no target matches → use DISAMBIGUATION_PRIORITY to pick most prominent.
     */
    private String pickBestIso(List<String> isos) {
        List<String> targets = isos.stream().filter(targetIsos::contains).toList();
        if (targets.size() == 1) return targets.get(0);
        if (targets.size() > 1) {
            // Multiple target matches — prefer non-exempt so visa check still runs
            return targets.stream()
                    .filter(iso -> !isVisaExempt(iso))
                    .findFirst()
                    .orElse(targets.get(0));
        }
        // No target match — pick by global prominence
        for (String preferred : DISAMBIGUATION_PRIORITY) {
            if (isos.contains(preferred)) return preferred;
        }
        return isos.get(0);
    }

    /** Whether this ISO is a target geography (eligible for job search). */
    public boolean isTargetCountry(String iso) {
        return iso != null && targetIsos.contains(iso);
    }

    /** Whether this ISO is visa-exempt (jobs here don't need visa check). Currently only DE. */
    public boolean isVisaExempt(String iso) {
        return "DE".equals(iso);
    }
}
