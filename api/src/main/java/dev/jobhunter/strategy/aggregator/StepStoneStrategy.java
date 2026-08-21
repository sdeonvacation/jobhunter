package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

@Slf4j
@Component
public class StepStoneStrategy implements FetchStrategy {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    private static final Pattern DETAIL_URL = Pattern.compile(
            "^https?://(?:www\\.)?stepstone\\.(?:de|at|nl|be)/stellenangebote--[^/?#]+-\\d+-inline\\.html(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_ID = Pattern.compile("--(\\d+)-inline\\.html", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_DELAY_MS = 1500;
    private static final int DEFAULT_MAX_SCRAPE = 40;
    private static final int DEFAULT_MAX_CONSECUTIVE_DETAIL_FAILURES = 3;
    private static final int DEFAULT_SEARCH_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_DETAIL_TIMEOUT_SECONDS = 15;

    private final WebClient webClient;
    private final JobPostingRepository jobPostingRepository;
    private final ObjectMapper objectMapper;

    public StepStoneStrategy(WebClient webClient, JobPostingRepository jobPostingRepository) {
        this.webClient = webClient;
        this.jobPostingRepository = jobPostingRepository;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "stepstone";
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of();
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String baseUrl = configString(context.config(), "url");
        List<String> keywords = context.keywords() == null
                ? List.of()
                : context.keywords().stream().filter(this::present).toList();
        if (!present(baseUrl)) return FetchResult.error("No URL configured for StepStone source", elapsed(start));
        if (keywords.isEmpty()) return FetchResult.error("No keywords configured for StepStone source", elapsed(start));

        int delayMs = parseInt(context.config(), "delayBetweenMs", DEFAULT_DELAY_MS);
        int maxScrape = parseInt(context.config(), "maxScrapePerRun", DEFAULT_MAX_SCRAPE);
        int maxConsecutiveDetailFailures = parseInt(context.config(), "maxConsecutiveDetailFailures",
                DEFAULT_MAX_CONSECUTIVE_DETAIL_FAILURES);
        int searchTimeout = parseInt(context.config(), "searchTimeoutSeconds", DEFAULT_SEARCH_TIMEOUT_SECONDS);
        int detailTimeout = parseInt(context.config(), "detailTimeoutSeconds", DEFAULT_DETAIL_TIMEOUT_SECONDS);
        List<String> cities = configList(context.config(), "cities");
        LinkedHashSet<String> detailUrls = new LinkedHashSet<>();

        try {
            boolean interrupted = false;
            for (String keyword : keywords) {
                if (cities.isEmpty()) {
                    interrupted = !fetchSearchPage(baseUrl, keyword, null, detailUrls, searchTimeout, delayMs);
                } else {
                    for (String city : cities) {
                        if (!fetchSearchPage(baseUrl, keyword, city, detailUrls, searchTimeout, delayMs)) {
                            interrupted = true;
                            break;
                        }
                    }
                }
                if (interrupted) break;
            }
        } catch (SearchAbortException e) {
            if (e.kind == FetchResultKind.RATE_LIMITED) return FetchResult.rateLimited(elapsed(start));
            if (e.kind == FetchResultKind.PROTECTED) return FetchResult.protectedEndpoint(elapsed(start));
            return FetchResult.error("StepStone search failed: " + e.detail, elapsed(start));
        } catch (Exception e) {
            log.error("[stepstone] Search harvesting failed: {}", e.getMessage());
            return FetchResult.error("StepStone search failed: " + e.getMessage(), elapsed(start));
        }

        Set<String> knownIds;
        try {
            knownIds = new HashSet<>(jobPostingRepository.findExternalIdsBySource(JobSource.STEPSTONE));
        } catch (Exception e) {
            log.warn("[stepstone] Could not load known IDs; proceeding without incremental deduplication: {}", e.getMessage());
            knownIds = Set.of();
        }
        final Set<String> knownIdsForFiltering = knownIds;

        List<String> candidates = detailUrls.stream()
                .filter(url -> !knownIdsForFiltering.contains(extractExternalId(url)))
                .limit(Math.max(0, maxScrape))
                .toList();
        log.info("[stepstone] Harvested {} links; scraping {} new details", detailUrls.size(), candidates.size());
        if (candidates.isEmpty()) return FetchResult.empty(elapsed(start));

        List<RawAggregatorJob> jobs = new ArrayList<>();
        boolean rateLimited = false;
        boolean transportFailure = false;
        int consecutiveDetailFailures = 0;
        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            String html = null;
            try {
                html = get(url, detailTimeout);
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 429) {
                    log.warn("[stepstone] Detail rate limited at {}; retaining {} jobs", url, jobs.size());
                    rateLimited = true;
                    break;
                }
                if (status == 404 || status == 410) {
                    log.debug("[stepstone] Detail page removed (HTTP {}) at {}", status, url);
                } else {
                    transportFailure = true;
                    consecutiveDetailFailures++;
                    log.warn("[stepstone] HTTP {} fetching detail {}", status, url);
                }
            } catch (Exception e) {
                transportFailure = true;
                consecutiveDetailFailures++;
                log.warn("[stepstone] Error fetching detail {}: {}", url, e.getMessage());
            }
            if (html != null && !html.isBlank()) {
                consecutiveDetailFailures = 0;
                try {
                    parsePage(html, url).ifPresent(jobs::add);
                } catch (Exception e) {
                    log.warn("[stepstone] Error parsing detail {}: {}", url, e.getMessage());
                }
            }
            if (consecutiveDetailFailures >= maxConsecutiveDetailFailures) {
                log.warn("[stepstone] Aborting detail fetch after {} consecutive failures",
                        consecutiveDetailFailures);
                break;
            }
            if (i < candidates.size() - 1 && !pause(delayMs)) break;
        }

        if (rateLimited && jobs.isEmpty()) return FetchResult.rateLimited(elapsed(start));
        if (jobs.isEmpty() && transportFailure) {
            return FetchResult.error("StepStone detail fetching failed", elapsed(start));
        }
        if (jobs.isEmpty()) return FetchResult.empty(elapsed(start));
        return FetchResult.success(jobs, elapsed(start));
    }

    private boolean fetchSearchPage(String baseUrl, String keyword, String city, Set<String> detailUrls,
                                    int timeoutSeconds, int delayMs) {
                    String searchUrl = searchUrl(baseUrl, keyword, city);
                    log.debug("[stepstone] Fetching search page {}", searchUrl);
                    String html;
                    try {
                        html = get(searchUrl, timeoutSeconds);
                    } catch (WebClientResponseException e) {
                        int status = e.getStatusCode().value();
                        if (status == 429) {
                            log.warn("[stepstone] Search rate limited at {}", searchUrl);
                            throw new SearchAbortException(FetchResultKind.RATE_LIMITED);
                        }
                        if (status == 403) {
                            log.warn("[stepstone] Search protected by anti-bot response at {}", searchUrl);
                            throw new SearchAbortException(FetchResultKind.PROTECTED);
                        }
                        throw new SearchAbortException(FetchResultKind.ERROR, e.getMessage());
                    }
                    if (html != null && !html.isBlank()) harvestLinks(html, searchUrl, baseUrl, detailUrls);
                    return pause(delayMs);
    }

    private enum FetchResultKind { ERROR, RATE_LIMITED, PROTECTED }
    private static class SearchAbortException extends RuntimeException {
        private final FetchResultKind kind;
        private final String detail;
        SearchAbortException(FetchResultKind kind) { this(kind, null); }
        SearchAbortException(FetchResultKind kind, String detail) { this.kind = kind; this.detail = detail; }
    }

    private String get(String url, int timeoutSeconds) {
        return webClient.get().uri(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Sec-CH-UA", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-CH-UA-Platform", "\"macOS\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.createException().flatMap(Mono::error);
                    }
                    return response.bodyToMono(byte[].class)
                            .map(body -> decodeBody(body, response.headers().asHttpHeaders()));
                })
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    private String decodeBody(byte[] body, HttpHeaders headers) {
        String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding == null || encoding.isBlank()) return new String(body, StandardCharsets.UTF_8);
        try (InputStream compressed = new ByteArrayInputStream(body);
             InputStream decoded = switch (encoding.toLowerCase(Locale.ROOT)) {
                 case "gzip" -> new GZIPInputStream(compressed);
                 case "deflate" -> new InflaterInputStream(compressed);
                 default -> throw new IllegalArgumentException("Unsupported Content-Encoding: " + encoding);
             }) {
            return new String(decoded.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not decode StepStone response", e);
        }
    }

    private void harvestLinks(String html, String searchUrl, String baseUrl, Set<String> links) {
        Document document = Jsoup.parse(html, searchUrl);
        for (Element anchor : document.select("a[href]")) {
            String absolute = anchor.absUrl("href");
            if (isAllowedDetailUrl(absolute, baseUrl)) links.add(absolute);
        }
    }

    private boolean isAllowedDetailUrl(String url, String baseUrl) {
        if (DETAIL_URL.matcher(url).matches()) return true;
        return !isStepStoneHost(baseUrl) && sameOrigin(url, baseUrl);
    }

    private boolean isStepStoneHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.matches("(?i)(?:www\\.)?stepstone\\.(?:de|at|nl|be)");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean sameOrigin(String first, String second) {
        try {
            URI left = URI.create(first);
            URI right = URI.create(second);
            return left.getScheme() != null && left.getHost() != null
                    && left.getScheme().equalsIgnoreCase(right.getScheme())
                    && left.getHost().equalsIgnoreCase(right.getHost())
                    && effectivePort(left) == effectivePort(right);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String searchUrl(String baseUrl, String keyword, String city) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = "/jobs/" + slug(keyword);
        if (present(city)) path += "/in-" + slug(city);
        return base + path + "?q=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8);
    }

    private String slug(String value) {
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String extractExternalId(String url) {
        Matcher matcher = EXTERNAL_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : String.valueOf(url.hashCode());
    }

    private java.util.Optional<RawAggregatorJob> parsePage(String html, String url) {
        Document document = Jsoup.parse(html, url);
        JsonNode posting = findJobPosting(document);
        String title = posting == null ? null : text(posting, "title");
        if (!present(title)) {
            Element h1 = document.selectFirst("h1");
            title = h1 == null ? null : h1.text();
        }
        if (!present(title)) {
            log.debug("[stepstone] Skipping untitled detail page {}", url);
            return java.util.Optional.empty();
        }
        String company = posting == null ? null : text(posting.path("hiringOrganization"), "name");
        String location = posting == null ? null : location(posting.path("jobLocation"));
        String description = posting == null ? null : text(posting, "description");
        LocalDate postedDate = posting == null ? null : date(text(posting, "datePosted"));
        Salary salary = posting == null ? new Salary(null, null, null) : salary(posting.path("baseSalary"));
        return java.util.Optional.of(new RawAggregatorJob(
                extractExternalId(url), title.trim(), company, location, description, url, postedDate,
                salary.min(), salary.max(), salary.currency(), posting == null ? null : posting.toString()));
    }

    private JsonNode findJobPosting(Document document) {
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                JsonNode result = findJobPosting(node);
                if (result != null) return result;
            } catch (Exception e) {
                log.debug("[stepstone] Ignoring malformed JSON-LD block: {}", e.getMessage());
            }
        }
        return null;
    }

    private JsonNode findJobPosting(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode result = findJobPosting(child);
                if (result != null) return result;
            }
        } else if (node.isObject()) {
            JsonNode type = node.path("@type");
            if ((type.isTextual() && "JobPosting".equalsIgnoreCase(type.asText()))
                    || (type.isArray() && containsType(type, "JobPosting"))) return node;
            JsonNode graph = node.get("@graph");
            JsonNode result = findJobPosting(graph);
            if (result != null) return result;
        }
        return null;
    }

    private boolean containsType(JsonNode types, String expected) {
        for (JsonNode type : types) if (expected.equalsIgnoreCase(type.asText())) return true;
        return false;
    }

    private String location(JsonNode locations) {
        List<String> cities = new ArrayList<>();
        if (locations.isArray()) for (JsonNode location : locations) addCity(cities, location);
        else addCity(cities, locations);
        return cities.isEmpty() ? null : String.join(", ", cities);
    }

    private void addCity(List<String> cities, JsonNode location) {
        String city = text(location.path("address"), "addressLocality");
        if (present(city)) cities.add(city);
    }

    private Salary salary(JsonNode baseSalary) {
        if (baseSalary == null || baseSalary.isMissingNode()) return new Salary(null, null, null);
        if (!"YEAR".equalsIgnoreCase(text(baseSalary, "unitText"))) return new Salary(null, null, null);
        JsonNode value = baseSalary.path("value");
        BigDecimal min = decimal(value, "minValue");
        BigDecimal max = decimal(value, "maxValue");
        if (value.isValueNode()) {
            BigDecimal scalar = decimal(value, null);
            min = scalar;
            max = scalar;
        }
        return new Salary(min, max, text(baseSalary, "currency"));
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = field == null ? node : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        try { return new BigDecimal(value.asText().replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String result = value.asText();
        return present(result) ? result : null;
    }

    private LocalDate date(String value) {
        if (!present(value)) return null;
        try { return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value); }
        catch (DateTimeParseException e) { return null; }
    }

    private List<String> configList(Map<String, Object> config, String key) {
        String value = configString(config, key);
        if (!present(value)) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(this::present).toList();
    }

    private String configString(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : value.toString();
    }

    private int parseInt(Map<String, Object> config, String key, int fallback) {
        try { return Integer.parseInt(configString(config, key)); }
        catch (Exception e) { return fallback; }
    }

    private boolean pause(int delayMs) {
        if (delayMs <= 0) return true;
        try { Thread.sleep(delayMs); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private Duration elapsed(Instant start) { return Duration.between(start, Instant.now()); }
    private record Salary(BigDecimal min, BigDecimal max, String currency) {}
}
