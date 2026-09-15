package dev.jobhunter.strategy.ats;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SuccessFactorsStrategy extends AbstractAtsStrategy {

    private static final int PAGE_SIZE = 25;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final long DETAIL_FETCH_DELAY_MS = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final DateTimeFormatter POSTED_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);
    private static final Pattern TOTAL_COUNT_PATTERN = Pattern.compile("Results\\s+\\d+\\s*[–-]\\s*\\d+\\s+of\\s+(\\d+)");
    private static final Pattern ARIA_TOTAL_PATTERN = Pattern.compile("Results\\s+\\d+\\s+to\\s+\\d+\\s+of\\s+(\\d+)");
    private static final Pattern SHOWING_TOTAL_PATTERN = Pattern.compile("Showing\\s+\\d+\\s+to\\s+\\d+\\s+of\\s+(\\d+)");

    // Classic SuccessFactors boards expose an XML listing API (e.g. career5.successfactors.eu)
    private static final Pattern CLASSIC_BOARD_PATTERN = Pattern.compile("career\\d*\\.successfactors\\.(eu|com)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPANY_PARAM_PATTERN = Pattern.compile("[?&]company=([^&#]*)", Pattern.CASE_INSENSITIVE);
    private static final String CLASSIC_XML_LOCALE = "en_GB";
    private static final Pattern TITLE_HYPHEN_CITY_PATTERN =
            Pattern.compile("-\\s*([A-ZÀ-Ü][a-zà-ü]+(?:\\s+[A-ZÀ-Ü][a-zà-ü]+)*)");
    // A numeric postcode directly after a hyphen segment is a strong location hint, e.g. "Velizy (78)".
    private static final Pattern POSTCODE_SUFFIX_PATTERN = Pattern.compile("^\\s*\\(\\d{2,6}\\)");
    private static final List<String> CLASSIC_COUNTRIES = List.of(
            "Germany", "Deutschland", "France", "Spain", "Netherlands", "Austria", "Switzerland", "Belgium",
            "Poland", "Portugal", "Italy", "United Kingdom", "Ireland", "Sweden", "Denmark", "Finland",
            "Czechia", "Romania", "Slovakia", "Slovenia", "Croatia", "Hungary", "Luxembourg");
    private static final List<String> CLASSIC_CITIES = List.of(
            "Düsseldorf", "Dusseldorf", "Munich", "München", "Berlin", "Cologne", "Köln", "Hamburg", "Duisburg",
            "Frankfurt", "Stuttgart", "Leipzig", "Dresden", "Nuremberg", "Nürnberg", "Bremen", "Hannover",
            "Amsterdam", "Rotterdam", "Brussels", "Bruxelles", "Vienna", "Wien", "Zurich", "Zürich", "Geneva",
            "Genf", "Basel", "Bern", "Lausanne", "Madrid", "Barcelona", "Valencia", "Seville", "Sevilla",
            "Zaragoza", "Vigo", "Albacete", "Murcia", "Palma", "Paris", "Lyon", "Toulouse", "Nice", "Grenoble",
            "Milan", "Milano", "Rome", "Warsaw", "Warszawa", "Krakow", "Kraków", "Wroclaw", "Wrocław",
            "Gdansk", "Gdańsk", "Lisbon", "Lisboa", "Porto", "Prague", "Bucharest", "Bratislava", "Ljubljana",
            "Zagreb", "Budapest", "Stockholm", "Copenhagen", "København", "Helsinki", "Dublin", "Antwerp",
            "Antwerpen", "Ghent", "Gent", "Eindhoven", "Utrecht", "The Hague", "Den Haag");
    // First word of every known country/city name, used to disambiguate trailing locations in titles
    private static final Set<String> LOCATION_FIRST_WORDS = locationFirstWords();

    private final WebClient webClient;

    public SuccessFactorsStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.SUCCESSFACTORS);
    }

    @Override
    public String name() {
        return "successfactors";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        if (isClassicBoard(endpoint.getUrl())) {
            return fetchClassic(endpoint);
        }
        String endpointUrl = endpoint.getUrl();
        String origin = extractOrigin(endpointUrl);
        Instant start = Instant.now();

        try {
            // Fetch first page to determine total count
            String firstPageHtml = fetchSearchPage(endpointUrl, 0);
            if (firstPageHtml == null || firstPageHtml.isBlank()) {
                log.info("SuccessFactors [{}]: empty response", endpointUrl);
                return FetchResult.empty(elapsed(start));
            }

            int totalCount = parseTotalCount(firstPageHtml);
            if (totalCount == 0) {
                log.info("SuccessFactors [{}]: no jobs found", endpointUrl);
                return FetchResult.empty(elapsed(start));
            }

            log.info("SuccessFactors [{}]: page 1, found {} total jobs", endpointUrl, totalCount);

            // Parse first page (detail hrefs are root-relative, so join against scheme://host only)
            List<JobListing> allListings = new ArrayList<>(parseListings(firstPageHtml, origin));

            // Fetch remaining pages
            int totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);
            for (int page = 2; page <= totalPages; page++) {
                int offset = (page - 1) * PAGE_SIZE;
                try {
                    String pageHtml = fetchSearchPage(endpointUrl, offset);
                    if (pageHtml != null && !pageHtml.isBlank()) {
                        List<JobListing> pageListings = parseListings(pageHtml, origin);
                        allListings.addAll(pageListings);
                        log.info("SuccessFactors [{}]: page {}/{}, accumulated {} listings",
                                endpointUrl, page, totalPages, allListings.size());
                    }
                } catch (Exception e) {
                    log.warn("SuccessFactors [{}]: failed to fetch page {} (offset {}): {}",
                            endpointUrl, page, offset, e.getMessage());
                }
            }

            if (allListings.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            // Fetch detail pages for descriptions, posted dates, and street addresses
            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (int i = 0; i < allListings.size(); i++) {
                JobListing listing = allListings.get(i);
                JobDetail detail = fetchDetail(listing.url());
                if (i > 0 && i % 50 == 0) {
                    log.info("SuccessFactors [{}]: fetched {}/{} detail pages",
                            endpointUrl, i, allListings.size());
                }

                String location = (listing.location() == null || listing.location().isBlank())
                        ? detail.streetAddress()
                        : listing.location();
                jobs.add(new RawAggregatorJob(
                        listing.externalId(),
                        listing.title(),
                        null,
                        location,
                        detail.description(),
                        listing.url(),
                        detail.postedDate(),
                        null,
                        null,
                        null,
                        null
                ));

                // Polite delay between detail fetches
                if (i < allListings.size() - 1) {
                    sleep(DETAIL_FETCH_DELAY_MS);
                }
            }

            log.info("SuccessFactors [{}]: extracted {} jobs", endpointUrl, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("SuccessFactors [{}]: access forbidden (403)", endpointUrl);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("SuccessFactors [{}]: unauthorized (401)", endpointUrl);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (Exception e) {
            log.error("SuccessFactors [{}]: extraction failed: {}", endpointUrl, e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    /**
     * Classic SuccessFactors boards (career*.successfactors.{eu,com} with a company= param) expose an
     * XML listing API on the singular /career path instead of the jobs2web HTML search UI.
     */
    private FetchResult fetchClassic(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String origin = extractOrigin(endpoint.getUrl());
        String company = extractCompany(endpoint.getUrl());
        if (origin == null) {
            log.warn("SuccessFactors [{}]: invalid classic board url", endpoint.getUrl());
            return FetchResult.error("classic SF: invalid endpoint url", elapsed(start));
        }
        if (company == null || company.isBlank()) {
            log.warn("SuccessFactors [{}]: classic board missing company param", endpoint.getUrl());
            return FetchResult.error("classic SF: missing company param", elapsed(start));
        }

        String xmlUrl = origin + "/career?company=" + company
                + "&&career_ns=job_listing_summary&&resultType=XML&&rcm_site_locale=" + CLASSIC_XML_LOCALE;
        try {
            String xml = webClient.get()
                    .uri(xmlUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(REQUEST_TIMEOUT);
            if (xml == null || xml.isBlank()) {
                log.info("SuccessFactors [{}]: classic board returned empty XML", endpoint.getUrl());
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = parseClassicXml(xml, origin, company);
            if (jobs.isEmpty()) {
                log.info("SuccessFactors [{}]: no jobs in classic board XML", endpoint.getUrl());
                return FetchResult.empty(elapsed(start));
            }
            log.info("SuccessFactors [{}]: classic board extracted {} jobs", endpoint.getUrl(), jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("SuccessFactors [{}]: access forbidden (403)", endpoint.getUrl());
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("SuccessFactors [{}]: unauthorized (401)", endpoint.getUrl());
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (Exception e) {
            log.error("SuccessFactors [{}]: classic board extraction failed: {}", endpoint.getUrl(), e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    boolean isClassicBoard(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return CLASSIC_BOARD_PATTERN.matcher(url).find()
                || url.toLowerCase(Locale.ROOT).contains("company=");
    }

    String extractCompany(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = COMPANY_PARAM_PATTERN.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        try {
            String decoded = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException e) {
            log.debug("SuccessFactors: failed to URL-decode company param: {}", e.getMessage());
            return null;
        }
    }

    String extractOrigin(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    List<RawAggregatorJob> parseClassicXml(String xml, String origin, String company) {
        Document doc = Jsoup.parse(xml);
        List<RawAggregatorJob> jobs = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Elements jobEls = doc.select("Job");
        for (Element jobEl : jobEls) {
            try {
                Element titleEl = jobEl.selectFirst("JobTitle");
                Element reqIdEl = jobEl.selectFirst("ReqId");
                if (titleEl == null || reqIdEl == null) {
                    continue;
                }
                String title = titleEl.text().trim();
                String reqId = reqIdEl.text().trim();
                if (title.isBlank() || reqId.isBlank()) {
                    continue;
                }
                if (!seenIds.add(reqId)) {
                    continue;
                }

                String description = extractClassicDescription(jobEl);
                String location = extractLocation(title, description);
                String applyUrl = origin + "/careers?company=" + company
                        + "&career_job_req_id=" + reqId
                        + "&career_ns=job_application&lang=en_GB";

                jobs.add(new RawAggregatorJob(reqId, title, null, location, description, applyUrl,
                        null, null, null, null, null));
            } catch (Exception e) {
                log.debug("SuccessFactors: failed to parse classic board job: {}", e.getMessage());
            }
        }
        return jobs;
    }

    /**
     * The Job-Description element wraps HTML markup in a CDATA section. Element text yields the raw
     * markup, so re-parse it to strip tags down to plain text.
     */
    private String extractClassicDescription(Element jobEl) {
        try {
            Element descEl = jobEl.selectFirst("Job-Description");
            if (descEl == null) {
                return null;
            }
            String rawMarkup = descEl.wholeOwnText();
            if (rawMarkup == null || rawMarkup.isBlank()) {
                return null;
            }
            String text = Jsoup.parse(rawMarkup).text().trim();
            if (text.isBlank()) {
                return null;
            }
            return truncate(text, MAX_DESCRIPTION_LENGTH);
        } catch (Exception e) {
            log.debug("SuccessFactors: failed to parse classic board description: {}", e.getMessage());
            return null;
        }
    }

    String extractLocation(String title, String description) {
        if (title != null && !title.isBlank()) {
            // Priority 1a: last hyphen-segment that is a known location, e.g. "... - CDI 35h - Velizy (78)".
            // The hyphen is not a location signal on its own: many boards use it to separate
            // seniority from function ("AVP-Ratings", "Asst Dir-Product Manager", "... - Banking").
            // Accept a segment only when it is a known place, or when it is followed by a
            // numeric postcode ("Velizy (78)").
            Matcher hyphenMatcher = TITLE_HYPHEN_CITY_PATTERN.matcher(title);
            String hyphenCity = null;
            while (hyphenMatcher.find()) {
                String candidate = hyphenMatcher.group(1);
                String firstWord = candidate.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                String after = title.substring(hyphenMatcher.end());
                boolean knownPlace = LOCATION_FIRST_WORDS.contains(firstWord);
                boolean followedByPostcode = POSTCODE_SUFFIX_PATTERN.matcher(after).lookingAt();
                if (knownPlace || followedByPostcode) {
                    hyphenCity = candidate;
                }
            }
            if (hyphenCity != null) {
                return hyphenCity;
            }

            // Priority 1b: trailing location in short titles, e.g. "Store Lead Albacete", "Store Lead Vigo Gran Vía"
            String titleEndCity = cityFromTitleEnd(title);
            if (titleEndCity != null) {
                return titleEndCity;
            }
        }

        if (description != null && !description.isBlank()) {
            String haystack = description.toLowerCase(Locale.ROOT);
            // Priority 2: known countries
            for (String country : CLASSIC_COUNTRIES) {
                if (containsWord(haystack, country.toLowerCase(Locale.ROOT))) {
                    return country;
                }
            }
            // Priority 3: major cities
            for (String city : CLASSIC_CITIES) {
                if (containsWord(haystack, city.toLowerCase(Locale.ROOT))) {
                    return city;
                }
            }
        }
        return null;
    }

    private String cityFromTitleEnd(String title) {
        String[] words = title.trim().split("\\s+");
        if (words.length == 0 || words.length > 6) {
            return null;
        }
        // Find the longest trailing suffix whose leading word is a known location (country/city).
        for (int start = 0; start < words.length; start++) {
            String firstWord = words[start];
            if (!firstWord.isEmpty()
                    && Character.isUpperCase(firstWord.charAt(0))
                    && LOCATION_FIRST_WORDS.contains(firstWord.toLowerCase(Locale.ROOT))) {
                return String.join(" ", java.util.Arrays.copyOfRange(words, start, words.length));
            }
        }
        return null;
    }

    /**
     * Whole-word (letter-boundary) containment check. A plain String.contains matches
     * "gent" inside "agent"/"urgent"/"intelligent", and \b does not treat umlauts
     * (Köln, Zürich, Düsseldorf) as word characters in Java by default.
     */
    private static boolean containsWord(String haystackLower, String needleLower) {
        int i = 0;
        while ((i = haystackLower.indexOf(needleLower, i)) >= 0) {
            boolean leftBoundary = i == 0 || !Character.isLetter(haystackLower.charAt(i - 1));
            int end = i + needleLower.length();
            boolean rightBoundary = end >= haystackLower.length() || !Character.isLetter(haystackLower.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            i = end;
        }
        return false;
    }

    private static Set<String> locationFirstWords() {
        Set<String> firstWords = new HashSet<>();
        for (String country : CLASSIC_COUNTRIES) {
            firstWords.add(country.toLowerCase(Locale.ROOT));
        }
        for (String city : CLASSIC_CITIES) {
            firstWords.add(city.toLowerCase(Locale.ROOT));
        }
        return firstWords;
    }

    String buildSearchUrl(String endpointUrl, int startRow) {
        String origin = extractOrigin(endpointUrl);
        if (origin == null) {
            return null;
        }
        String q = "";
        String locationSearch = "Germany";
        String locale = "en_US";
        int queryStart = endpointUrl.indexOf('?');
        if (queryStart >= 0 && queryStart < endpointUrl.length() - 1) {
            // Reuse raw (already-encoded) values verbatim to avoid double-encoding
            String rawQuery = endpointUrl.substring(queryStart + 1);
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                switch (name.toLowerCase(Locale.ROOT)) {
                    case "q" -> q = value;
                    case "locationsearch" -> locationSearch = value;
                    case "locale" -> locale = value;
                    default -> { /* ignore unrelated params */ }
                }
            }
        }
        return origin + "/search/?q=" + q + "&locationsearch=" + locationSearch
                + "&locale=" + locale + "&startrow=" + startRow;
    }

    private String fetchSearchPage(String endpointUrl, int startRow) {
        String url = buildSearchUrl(endpointUrl, startRow);
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    private String fetchDetailPage(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    int parseTotalCount(String html) {
        // Try aria-label first: 'Results 1 to 25 of 303'
        Matcher ariaMatcher = ARIA_TOTAL_PATTERN.matcher(html);
        if (ariaMatcher.find()) {
            try {
                return Integer.parseInt(ariaMatcher.group(1));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        // Try jobs2web/TalentBrew pattern: 'Showing 1 to 25 of 303'
        Matcher showingMatcher = SHOWING_TOTAL_PATTERN.matcher(html);
        if (showingMatcher.find()) {
            try {
                return Integer.parseInt(showingMatcher.group(1));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        // Fallback: strip inline tags and try 'Results 1 – 25 of 303'
        String stripped = html.replaceAll("</?b>", "");
        Matcher matcher = TOTAL_COUNT_PATTERN.matcher(stripped);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    List<JobListing> parseListings(String html, String baseUrl) {
        Document doc = Jsoup.parse(html);
        List<JobListing> listings = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // Try table rows first (most SuccessFactors sites use a table layout)
        Elements tableRows = doc.select("tr");
        for (Element row : tableRows) {
            Element link = row.selectFirst("a[href~=/job/.+/\\d+/]");
            if (link == null) continue;

            try {
                String href = link.attr("href");
                String externalId = extractExternalId(href);
                String title = link.text().trim();

                if (externalId == null || externalId.isBlank() || title.isBlank()) {
                    continue;
                }
                if (!seenIds.add(externalId)) {
                    continue;
                }

                String fullUrl = toAbsoluteUrl(href, baseUrl);

                // Extract location from sibling table cell or text node
                String location = null;
                Elements cells = row.select("td");
                if (cells.size() >= 2) {
                    location = cells.get(1).text().trim();
                }
                if (location == null || location.isBlank()) {
                    location = extractLocationFromUrl(href);
                }

                listings.add(new JobListing(externalId, title, truncate(location, 500), fullUrl));
            } catch (Exception e) {
                log.debug("SuccessFactors: failed to parse listing row: {}", e.getMessage());
            }
        }

        // Fallback: if no table rows found, try bare links
        if (listings.isEmpty()) {
            Elements links = doc.select("a[href~=/job/.+/\\d+/]");
            for (Element link : links) {
                try {
                    String href = link.attr("href");
                    String externalId = extractExternalId(href);
                    String title = link.text().trim();

                    if (externalId == null || externalId.isBlank() || title.isBlank()) {
                        continue;
                    }
                    if (!seenIds.add(externalId)) {
                        continue;
                    }

                    String fullUrl = toAbsoluteUrl(href, baseUrl);
                    String location = extractLocationFromUrl(href);
                    listings.add(new JobListing(externalId, title, truncate(location, 500), fullUrl));
                } catch (Exception e) {
                    log.debug("SuccessFactors: failed to parse listing link: {}", e.getMessage());
                }
            }
        }

        return listings;
    }

    private JobDetail fetchDetail(String jobUrl) {
        try {
            String html = fetchDetailPage(jobUrl);
            if (html == null || html.isBlank()) {
                return new JobDetail(null, null, null);
            }
            return new JobDetail(parseDescription(html), parsePostedDate(html), parseStreetAddress(html));
        } catch (Exception e) {
            log.debug("SuccessFactors: failed to fetch detail page {}: {}", jobUrl, e.getMessage());
            return new JobDetail(null, null, null);
        }
    }

    LocalDate parsePostedDate(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Element dateMeta = Jsoup.parse(html).selectFirst("meta[itemprop=datePosted]");
            if (dateMeta == null) {
                return null;
            }
            String content = dateMeta.attr("content");
            if (content == null || content.isBlank()) {
                return null;
            }
            return ZonedDateTime.parse(content, POSTED_DATE_FORMATTER).toLocalDate();
        } catch (Exception e) {
            log.debug("SuccessFactors: failed to parse datePosted microdata: {}", e.getMessage());
            return null;
        }
    }

    String parseStreetAddress(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Element addressMeta = Jsoup.parse(html).selectFirst("meta[itemprop=streetAddress]");
        if (addressMeta == null) {
            return null;
        }
        String content = addressMeta.attr("content");
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.trim();
    }

    String parseDescription(String html) {
        Document doc = Jsoup.parse(html);

        // Try common SuccessFactors description containers.
        // CSB detail pages wrap the full JD in .jobDisplay .content. The nested
        // .jobdescription / [itemprop=description] spans only cover the first
        // section on pages with malformed <p>-in-<p> nesting (Jsoup closes the
        // span early), so prefer the outer container first.
        Element descriptionEl = doc.selectFirst(".jobDisplay .content");
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst("[itemprop=description]");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst(".jobdescription");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst(".job-description");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst("[class*=jobDescription]");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst(".contentWithSidePanel__content");
        }
        if (descriptionEl == null) {
            // Fallback: main content area
            descriptionEl = doc.selectFirst("main");
        }
        if (descriptionEl == null) {
            descriptionEl = doc.selectFirst("#content");
        }

        if (descriptionEl == null) {
            return null;
        }

        String text = descriptionEl.text().trim();
        return truncate(text, MAX_DESCRIPTION_LENGTH);
    }

    String extractExternalId(String href) {
        // URL pattern: /job/{...}/{NumericId}/
        String cleaned = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String candidate = cleaned.substring(lastSlash + 1);
        // Verify it's numeric
        if (candidate.matches("\\d+")) {
            return candidate;
        }
        return null;
    }

    private String extractLocationFromUrl(String href) {
        // Pattern: /job/{City}-{Title}-{PostalCode}/{NumericId}/
        // Extract the city from the first segment after /job/
        String path = href.startsWith("http") ? href.replaceFirst("https?://[^/]+", "") : href;
        String[] segments = path.split("/");
        // segments: ["", "job", "{City}-{Title}-{PostalCode}", "{NumericId}"]
        if (segments.length >= 3) {
            String jobSegment = segments[2];
            // City is the first part before the first hyphen
            int firstHyphen = jobSegment.indexOf('-');
            if (firstHyphen > 0) {
                return jobSegment.substring(0, firstHyphen).replace("%20", " ");
            }
            return jobSegment;
        }
        return null;
    }

    private String toAbsoluteUrl(String href, String baseUrl) {
        if (href.startsWith("http")) {
            return href;
        }
        return baseUrl + (href.startsWith("/") ? href : "/" + href);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Internal record for intermediate listing data
    record JobListing(String externalId, String title, String location, String url) {}

    // Internal record for parsed detail-page data (single fetch, no second HTTP request)
    record JobDetail(String description, LocalDate postedDate, String streetAddress) {}
}
