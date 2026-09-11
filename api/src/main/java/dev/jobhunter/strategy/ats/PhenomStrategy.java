package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes Phenom People career sites (e.g. https://careers.enbw.com/de/de).
 *
 * <p>Discovery uses the server-rendered {@code /sitemap.xml}: every {@code <loc>} containing
 * {@code /job/} is a job detail page. Each detail page embeds a schema.org {@code JobPosting}
 * JSON-LD block ({@code <script type="application/ld+json">}) holding title, dates, location,
 * identifier and an HTML description (entity-escaped, so it must be unescaped before stripping).</p>
 */
@Slf4j
@Component
public class PhenomStrategy extends AbstractAtsStrategy {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final long DETAIL_FETCH_DELAY_MS = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final int MAX_JOBS = 500;
    private static final String SITEMAP_PATH = "/sitemap.xml";
    private static final String JOB_PATH_SEGMENT = "/job/";
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    private static final Pattern COLONLESS_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PhenomStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.PHENOM);
    }

    @Override
    public String name() {
        return "phenom";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        String baseUrl = stripTrailingSlash(endpoint.getUrl());
        Instant start = Instant.now();

        try {
            List<String> jobUrls = extractJobUrls(fetchHtml(baseUrl + SITEMAP_PATH), baseUrl);
            if (jobUrls.isEmpty()) {
                log.info("Phenom [{}]: no /job/ URLs in sitemap", baseUrl);
                return FetchResult.empty(elapsed(start));
            }
            if (jobUrls.size() > MAX_JOBS) {
                log.warn("Phenom [{}]: sitemap lists {} jobs, capping at {}", baseUrl, jobUrls.size(), MAX_JOBS);
                jobUrls = jobUrls.subList(0, MAX_JOBS);
            }
            log.info("Phenom [{}]: sitemap lists {} job URLs", baseUrl, jobUrls.size());

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (int i = 0; i < jobUrls.size(); i++) {
                if (i > 0) {
                    sleep(DETAIL_FETCH_DELAY_MS);
                }
                String jobUrl = jobUrls.get(i);
                try {
                    RawAggregatorJob job = buildJob(jobUrl, endpoint);
                    if (job != null) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    log.warn("Phenom [{}]: failed to fetch job {}: {}", baseUrl, jobUrl, e.getMessage());
                }
            }

            if (jobs.isEmpty()) {
                log.info("Phenom [{}]: no usable jobs extracted", baseUrl);
                return FetchResult.empty(elapsed(start));
            }
            log.info("Phenom [{}]: extracted {} jobs", baseUrl, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("Phenom [{}]: access forbidden (403)", baseUrl);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("Phenom [{}]: unauthorized (401)", baseUrl);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (Exception e) {
            log.error("Phenom [{}]: extraction failed: {}", baseUrl, e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    /**
     * Parses the sitemap XML and returns absolute job detail URLs, in document order. Returns an
     * empty list when the sitemap has no {@code /job/} entries.
     */
    private List<String> extractJobUrls(String xml, String baseUrl) {
        List<String> urls = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return urls;
        }
        Document sitemap = Jsoup.parse(xml, "", Parser.xmlParser());
        for (Element loc : sitemap.select("loc")) {
            String value = loc.text();
            if (value == null || value.isBlank() || !value.contains(JOB_PATH_SEGMENT)) {
                continue;
            }
            urls.add(resolveUrl(value.trim(), baseUrl));
        }
        return urls;
    }

    /**
     * Fetches and maps a single job detail page. Returns {@code null} (skips the job) when the page
     * has no {@code JobPosting} JSON-LD or cannot be parsed; detail failures are best-effort.
     */
    private RawAggregatorJob buildJob(String detailUrl, CareerEndpoint endpoint) {
        JsonNode posting = extractJobPosting(fetchHtml(detailUrl));
        if (posting == null) {
            log.warn("Phenom: no JobPosting JSON-LD on {}", detailUrl);
            return null;
        }

        String externalId = firstNonBlank(
                textOrNull(posting.path("identifier"), "value"),
                externalIdFromUrl(detailUrl));
        String title = collapseWhitespace(textOrNull(posting, "title"));
        String location = extractLocation(posting);
        String companyName = firstNonBlank(
                textOrNull(posting.path("hiringOrganization"), "name"),
                endpointCompanyName(endpoint));
        String description = truncate(
                stripHtml(Parser.unescapeEntities(posting.path("description").asText(""), true)),
                MAX_DESCRIPTION_LENGTH);

        return new RawAggregatorJob(
                externalId,
                title,
                companyName,
                location,
                description,
                detailUrl,
                parseIsoDate(normalizeOffset(textOrNull(posting, "datePosted"))),
                null,
                null,
                null,
                posting.toString());
    }

    /**
     * Finds the first schema.org {@code JobPosting} node among the page's JSON-LD blocks. Handles a
     * single object, an array of objects, and a textual or array {@code @type}.
     */
    private JsonNode extractJobPosting(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        for (Element script : Jsoup.parse(html).select("script[type=application/ld+json]")) {
            String json = script.data();
            if (json == null || json.isBlank()) {
                json = script.html();
            }
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(json);
                JsonNode posting = findJobPosting(parsed);
                if (posting != null) {
                    return posting;
                }
            } catch (Exception e) {
                log.debug("Phenom: skipped unparseable JSON-LD block: {}", e.getMessage());
            }
        }
        return null;
    }

    private JsonNode findJobPosting(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode posting = findJobPosting(item);
                if (posting != null) {
                    return posting;
                }
            }
            return null;
        }
        if (hasType(node.path("@type"), "JobPosting")) {
            return node;
        }
        return null;
    }

    private boolean hasType(JsonNode typeNode, String expected) {
        if (typeNode.isTextual()) {
            return expected.equals(typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() && expected.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code jobLocation} may be a single object or an array of places. */
    private String extractLocation(JsonNode posting) {
        JsonNode locationNode = posting.path("jobLocation");
        if (locationNode.isArray()) {
            if (locationNode.isEmpty()) {
                return null;
            }
            locationNode = locationNode.get(0);
        }
        JsonNode address = locationNode.path("address");
        if (address.isMissingNode() || address.isNull()) {
            return null;
        }
        return joinNonBlank(", ",
                textOrNull(address, "addressLocality"),
                textOrNull(address, "addressCountry"));
    }

    /** Falls back to the numeric req id embedded in {@code .../job/EBQEBQGLOBAL{reqId}EXTERNAL.../}. */
    private String externalIdFromUrl(String detailUrl) {
        if (detailUrl == null) {
            return null;
        }
        int jobIdx = detailUrl.indexOf(JOB_PATH_SEGMENT);
        if (jobIdx < 0) {
            return null;
        }
        String remainder = detailUrl.substring(jobIdx + JOB_PATH_SEGMENT.length());
        int slash = remainder.indexOf('/');
        String segment = slash >= 0 ? remainder.substring(0, slash) : remainder;
        Matcher matcher = DIGITS.matcher(segment);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String endpointCompanyName(CareerEndpoint endpoint) {
        if (endpoint == null || endpoint.getCompany() == null) {
            return null;
        }
        return endpoint.getCompany().getName();
    }

    private String fetchHtml(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    private String collapseWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Phenom emits timestamps with a colonless UTC offset (e.g. {@code 2026-09-10T00:00:00.000+0000}),
     * which {@link java.time.ZonedDateTime#parse} rejects. Insert the missing colon so the inherited
     * {@link #parseIsoDate(String)} can handle it.
     */
    private String normalizeOffset(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return COLONLESS_OFFSET.matcher(value).replaceFirst("$1:$2");
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return baseUrl + (url.startsWith("/") ? url : "/" + url);
    }

    private String joinNonBlank(String separator, String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
