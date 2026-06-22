package dev.jobhunter.util;

/**
 * Parses raw location strings into structured country code and city.
 * Handles formats from LinkedIn, SmartRecruiters, Personio, Workday, etc.
 */
public class LocationCountryParser {

    private LocationCountryParser() {}

    /** Returns ISO 3166-1 alpha-2 country code (lowercase), or null if undetected. */
    public static String extractCountry(String location) {
        if (location == null || location.isBlank()) return null;
        String loc = location.toLowerCase();

        if (containsAny(loc, "germany", "deutschland", ", de ", ", de,", "(de)", "berlin", "munich", "münchen",
                "hamburg", "frankfurt", "cologne", "köln", "stuttgart", "düsseldorf", "dusseldorf",
                "nuremberg", "nürnberg", "leipzig", "dresden", "hannover", "bremen", "dortmund",
                "essen", "duisburg", "bochum", "wuppertal", "bielefeld", "bonn", "karlsruhe",
                "mannheim", "augsburg", "münster", "wiesbaden", "freiburg", "potsdam", "erfurt",
                "rostock", "mainz", "lübeck"))
            return "de";
        if (containsAny(loc, "netherlands", "nederland", ", nl ", ", nl,", "(nl)", "amsterdam",
                "rotterdam", "the hague", "den haag", "eindhoven", "utrecht", "groningen", "tilburg",
                "almere", "breda", "nijmegen", "haarlem", "arnhem", "amersfoort", "apeldoorn",
                "s-hertogenbosch", "enschede", "leiden", "maastricht", "dordrecht", "zoetermeer",
                "zwolle", "deventer", "delft", "alkmaar", "north holland", "south holland",
                "noord-holland", "zuid-holland"))
            return "nl";
        if (containsAny(loc, "sweden", "sverige", ", se ", ", se,", "(se)", "gothenburg", "göteborg",
                "stockholm", "malmö", "malmoe", "uppsala", "västerås", "örebro", "linköping",
                "helsingborg", "jönköping", "norrköping", "lund", "umeå"))
            return "se";
        if (containsAny(loc, "austria", "österreich", "wien", "vienna", "graz", "linz", "salzburg",
                "innsbruck", ", at ", ", at,", "(at)"))
            return "at";
        if (containsAny(loc, "switzerland", "schweiz", "zürich", "zurich", "geneva", "genf", "basel",
                "bern", "lausanne", ", ch ", ", ch,", "(ch)"))
            return "ch";
        if (containsAny(loc, "poland", "polska", "warsaw", "warszawa", "krakow", "kraków", "wroclaw",
                "wrocław", "poznan", "poznań", "gdansk", "gdańsk", ", pl ", ", pl,"))
            return "pl";
        if (containsAny(loc, "spain", "españa", "madrid", "barcelona", "valencia", "seville", "sevilla",
                ", es ", ", es,", "(es)"))
            return "es";
        if (containsAny(loc, "france", "paris", "lyon", "marseille", "toulouse", "nice",
                ", fr ", ", fr,", "(fr)"))
            return "fr";
        if (containsAny(loc, "united kingdom", "england", "london", "manchester", "birmingham",
                "leeds", "glasgow", "edinburgh", ", gb ", ", gb,", "(gb)", "(uk)"))
            return "gb";
        return null;
    }

    /** Returns best-guess city name, or null. */
    public static String extractCity(String location) {
        if (location == null || location.isBlank()) return null;
        // Strip trailing parenthetical (e.g. "(Hybrid)", "(Remote)")
        String cleaned = location.replaceAll("(?i)\\s*\\(.*\\)\\s*$", "").trim();
        // Take first segment before comma, slash, angle-bracket, or ">"
        String[] parts = cleaned.split("[,>/]");
        if (parts.length > 0) {
            String city = parts[0].trim();
            // Skip known non-city tokens
            if (city.length() > 2
                    && !city.equalsIgnoreCase("remote")
                    && !city.equalsIgnoreCase("germany")
                    && !city.equalsIgnoreCase("netherlands")
                    && !city.equalsIgnoreCase("sweden")
                    && !city.equalsIgnoreCase("austria")
                    && !city.equalsIgnoreCase("france")
                    && !city.equalsIgnoreCase("spain")) {
                return city;
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) return true;
        }
        return false;
    }
}
