package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin board implementation for wissenschaftsstellen.de.
 *
 * <p>Listing pages ({@code GET {base}/?seite=N}, 50 jobs/page, newest-first) embed a JS
 * global {@code JOBS=[...]} array with structured metadata (no description) plus
 * {@code /stelle/<slug>-<id>} anchors used to resolve each job's detail URL. Detail pages
 * embed a {@code JobPosting} JSON-LD block supplying the description, {@code datePosted},
 * location and {@code baseSalary}. Shared enumeration/dedup/bounds/politeness live in
 * {@link UniversityBoardStrategy}.
 */
@Slf4j
@Component
public class WissenschaftsstellenStrategy extends UniversityBoardStrategy {

    private static final String SITE_BASE = "https://wissenschaftsstellen.de/";
    private static final String SCOPE_CATEGORY = "Technik/Labor";
    private static final String JOBS_MARKER = "JOBS=";
    private static final Pattern STELLE_ID = Pattern.compile("/stelle/(?:[^/?#]*)-([0-9]+)(?:[?#].*)?$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public WissenschaftsstellenStrategy(WebClient webClient) {
        super(webClient);
    }

    @Override
    public String name() {
        return "wissenschaftsstellen";
    }

    @Override
    protected String buildListingUrl(String baseUrl, int page) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/?seite=" + page;
    }

    @Override
    protected List<BoardJob> parseListingJobs(String html) {
        if (html == null || html.isBlank()) {
            throw new ListingParseException(name() + " listing page is blank");
        }
        Map<String, String> detailUrls = extractDetailUrls(html);
        String arrayJson = extractJobsArray(html);
        if (arrayJson == null) {
            throw new ListingParseException(name() + " listing is missing the JOBS= array");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(arrayJson);
        } catch (Exception e) {
            throw new ListingParseException(name() + " JOBS array is not valid JSON: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw new ListingParseException(name() + " JOBS payload is not an array");
        }

        List<BoardJob> jobs = new ArrayList<>();
        for (JsonNode node : root) {
            String id = text(node, "id");
            String title = text(node, "titel");
            if (!present(id) || !present(title)) {
                continue; // malformed row: id/title are the mandatory keys
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("kategorie", text(node, "kategorie"));
            attributes.put("befristung", text(node, "befristung"));
            attributes.put("arbeitszeit_pct", text(node, "arbeitszeit_pct"));
            attributes.put("entgeltgruppe", text(node, "entgeltgruppe"));
            attributes.put("tags", text(node, "tags"));
            attributes.put("quelle", text(node, "quelle"));
            attributes.put("inst_typ", text(node, "inst_typ"));
            attributes.put("fachbereich_raw", text(node, "fachbereich_raw"));

            jobs.add(new BoardJob(
                    id,
                    title,
                    text(node, "hochschule"),
                    text(node, "bundesland"), // listing province is the job location
                    text(node, "link"),
                    detailUrls.get(id),        // null when no matching anchor
                    attributes));
        }
        return jobs;
    }

    @Override
    protected boolean matchesScope(BoardJob job) {
        if (job == null || job.attributes() == null) {
            return false;
        }
        return SCOPE_CATEGORY.equals(String.valueOf(job.attributes().get("kategorie")));
    }

    @Override
    protected RawAggregatorJob parseDetail(String html, BoardJob job) {
        JsonNode posting = (html == null || html.isBlank()) ? null : findJobPosting(html);

        String title = job == null ? null : job.title();
        String company = job == null ? null : job.companyName();
        String listingLoc = job == null ? null : job.location();
        String location = listingLoc;
        String applyUrl = job == null ? null : job.applyUrl();
        String detailUrl = job == null ? null : job.detailUrl();
        String description = null;

        Map<String, Object> raw = new LinkedHashMap<>();
        if (job != null && job.attributes() != null) {
            raw.putAll(job.attributes());
        }

        if (posting != null) {
            String jsonLdTitle = text(posting, "title");
            if (present(jsonLdTitle)) {
                title = jsonLdTitle;
            }

            String jsonLdCompany = text(posting.path("hiringOrganization"), "name");
            if (present(jsonLdCompany)) {
                company = jsonLdCompany;
            }

            // Prefer the listing bundesland: the JSON-LD address is often the employer HQ.
            // Append the JSON-LD country code so bare state names ("Bayern") become geo-resolvable
            // ("Bayern, DE") without hardcoding Germany (the board also lists AT/CH institutions).
            String jsonLdCountry = country(posting.path("jobLocation"));
            if (present(listingLoc) && present(jsonLdCountry)) {
                location = listingLoc + ", " + jsonLdCountry;
            } else if (!present(listingLoc)) {
                String jsonLdLocation = location(posting.path("jobLocation"));
                if (!present(jsonLdLocation)) {
                    jsonLdLocation = jsonLdCountry;
                }
                if (present(jsonLdLocation)) {
                    location = jsonLdLocation;
                }
            }

            String jsonLdUrl = text(posting, "url");
            if (!present(applyUrl) && present(jsonLdUrl)) {
                applyUrl = jsonLdUrl;
            }

            String descriptionHtml = text(posting, "description");
            if (present(descriptionHtml)) {
                description = Jsoup.parse(descriptionHtml).text().trim();
            }

            // Board postings are often older than yesterday; aggregator sources surface via
            // discoveredDate, so postedDate stays null or the Daily Digest would hide them.
            // Keep the real date in rawJson for reference only.
            String datePosted = text(posting, "datePosted");
            if (present(datePosted)) {
                raw.put("datePosted", datePosted);
            }

            JsonNode baseSalary = posting.path("baseSalary");
            if (!baseSalary.isMissingNode() && !baseSalary.isNull()) {
                raw.put("baseSalary", baseSalary);
            }
            JsonNode employmentType = posting.path("employmentType");
            if (!employmentType.isMissingNode() && !employmentType.isNull()) {
                raw.put("employmentType", employmentType);
            }
            if (present(jsonLdUrl)) {
                raw.put("jsonLdUrl", jsonLdUrl);
            }
        }

        if (!present(title)) {
            return null; // no JSON-LD and no listing fallback title
        }
        if (!present(applyUrl)) {
            applyUrl = detailUrl;
        }

        return new RawAggregatorJob(
                job == null ? null : job.externalId(),
                title,
                company,
                location,
                description,
                applyUrl,
                null, // postedDate intentionally null: aggregator sources surface via discoveredDate
                null, null, null, // monthly baseSalary is preserved in rawJson, not mapped
                toJson(raw));
    }

    // ── Listing extraction ────────────────────────────────────────────────────

    private Map<String, String> extractDetailUrls(String html) {
        Map<String, String> byId = new HashMap<>();
        Document document = Jsoup.parse(html);
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            if (!present(href) || !href.contains("/stelle/")) {
                continue;
            }
            Matcher matcher = STELLE_ID.matcher(href);
            if (matcher.find()) {
                byId.putIfAbsent(matcher.group(1), absolutize(href));
            }
        }
        return byId;
    }

    private String absolutize(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            return URI.create(SITE_BASE).resolve(href).toString();
        } catch (IllegalArgumentException e) {
            return href;
        }
    }

    private String extractJobsArray(String html) {
        int marker = html.indexOf(JOBS_MARKER);
        while (marker >= 0) {
            int start = html.indexOf('[', marker + JOBS_MARKER.length());
            if (start < 0) {
                return null;
            }
            int end = matchingBracket(html, start);
            if (end > start) {
                return html.substring(start, end + 1);
            }
            marker = html.indexOf(JOBS_MARKER, marker + 1);
        }
        return null;
    }

    /** Returns the index of the bracket matching the one at {@code openIndex}, or -1. */
    private int matchingBracket(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ── Detail JSON-LD extraction ─────────────────────────────────────────────

    private JsonNode findJobPosting(String html) {
        Document document = Jsoup.parse(html);
        for (Element script : document.select("script[type=application/ld+json]")) {
            String data = script.data();
            if (!present(data)) {
                continue;
            }
            try {
                JsonNode found = findJobPosting(objectMapper.readTree(data));
                if (found != null) {
                    return found;
                }
            } catch (Exception e) {
                log.debug("[wissenschaftsstellen] Ignoring malformed JSON-LD: {}", e.getMessage());
            }
        }
        return null;
    }

    private JsonNode findJobPosting(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findJobPosting(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isObject()) {
            JsonNode graph = node.get("@graph");
            JsonNode found = findJobPosting(graph);
            if (found != null) {
                return found;
            }
            if (hasType(node.path("@type"), "JobPosting")) {
                return node;
            }
        }
        return null;
    }

    private boolean hasType(JsonNode type, String expected) {
        if (type.isTextual()) {
            return expected.equalsIgnoreCase(type.asText());
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (expected.equalsIgnoreCase(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String location(JsonNode locations) {
        if (locations == null || locations.isMissingNode() || locations.isNull()) {
            return null;
        }
        if (locations.isArray()) {
            for (JsonNode child : locations) {
                String city = location(child);
                if (present(city)) {
                    return city;
                }
            }
            return null;
        }
        if (locations.isTextual()) {
            return locations.asText();
        }
        return text(locations.path("address"), "addressLocality");
    }

    /** Extracts the JSON-LD {@code addressCountry} ISO code, tolerating Place arrays. */
    private String country(JsonNode locations) {
        if (locations == null || locations.isMissingNode() || locations.isNull()) {
            return null;
        }
        if (locations.isArray()) {
            for (JsonNode child : locations) {
                String value = country(child);
                if (present(value)) {
                    return value;
                }
            }
            return null;
        }
        if (locations.isObject()) {
            return text(locations.path("address"), "addressCountry");
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return present(text) ? text.trim() : null;
    }

    private String toJson(Map<String, Object> raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            return raw.toString();
        }
    }
}
