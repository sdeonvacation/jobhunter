package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class JobleadsStrategy implements FetchStrategy {

    private static final String SITE_ORIGIN = "https://www.jobleads.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    private static final long QUERY_DELAY_MS = 500L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** FlareSolverr may take up to its own maxTimeout (60s) plus transport overhead. */
    private static final Duration FLARESOLVERR_TIMEOUT = Duration.ofSeconds(70);
    private static final Pattern DAYS_AGO = Pattern.compile("(\\d+)\\s*Tage", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_PREFIX = Pattern.compile("^([A-Z]{3})(?=\\s|$|\\d)");
    private static final Pattern CHALLENGE_BODY =
            Pattern.compile("just a moment|cf-challenge|captcha", Pattern.CASE_INSENSITIVE);

    private final WebClient webClient;
    private final String flaresolverrBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobleadsStrategy(
            WebClient webClient,
            @Value("${jobhunter.flaresolverr.base-url:http://localhost:8191/v1}") String flaresolverrBaseUrl) {
        this.webClient = webClient;
        this.flaresolverrBaseUrl = flaresolverrBaseUrl;
    }

    @Override
    public String name() {
        return "jobleads";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String baseUrl = configString(context.config(), "url");
        if (!present(baseUrl)) {
            return FetchResult.error("No URL configured for jobleads source", elapsed(start));
        }

        List<String> keywords = context.keywords() == null
                ? List.of()
                : context.keywords().stream().filter(this::present).toList();

        List<String> urls;
        try {
            urls = buildUrls(baseUrl, keywords);
        } catch (IllegalArgumentException e) {
            return FetchResult.error(e.getMessage(), elapsed(start));
        }

        Map<String, RawAggregatorJob> byId = new LinkedHashMap<>();
        boolean anyResponse = false;

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                String html = fetchHtml(url);
                anyResponse = true;
                if (present(html)) {
                    for (RawAggregatorJob job : parseCards(html)) {
                        byId.putIfAbsent(job.externalId(), job);
                    }
                    if (byId.isEmpty() && CHALLENGE_BODY.matcher(html).find()) {
                        return FetchResult.error("Jobleads returned a bot-challenge page", elapsed(start));
                    }
                }
            } catch (Exception e) {
                log.warn("Jobleads request failed for {}: {}", url, e.getMessage());
            }

            if (context.maxResults() > 0 && byId.size() >= context.maxResults()) {
                break;
            }
            if (i < urls.size() - 1) {
                pause(QUERY_DELAY_MS);
            }
        }

        if (byId.isEmpty()) {
            if (!anyResponse) {
                return FetchResult.error("Jobleads fetch failed for all queries", elapsed(start));
            }
            return FetchResult.empty(elapsed(start));
        }

        List<RawAggregatorJob> jobs = new ArrayList<>(byId.values());
        if (context.maxResults() > 0 && jobs.size() > context.maxResults()) {
            jobs = new ArrayList<>(jobs.subList(0, context.maxResults()));
        }
        log.info("Jobleads fetched {} jobs across {} queries", jobs.size(), urls.size());
        return FetchResult.success(jobs, elapsed(start));
    }

    /** Builds one request URL per keyword: {query} template, path append, or the url as-is. */
    private List<String> buildUrls(String baseUrl, List<String> keywords) {
        if (keywords.isEmpty()) {
            return List.of(baseUrl);
        }
        List<String> urls = new ArrayList<>(keywords.size());
        if (baseUrl.contains("{query}")) {
            for (String keyword : keywords) {
                urls.add(baseUrl.replace("{query}", encode(keyword)));
            }
            return urls;
        }
        if (!baseUrl.endsWith("/")) {
            throw new IllegalArgumentException(
                    "Jobleads URL has no {query} placeholder and does not end with /: " + baseUrl);
        }
        for (String keyword : keywords) {
            urls.add(baseUrl + encode(keyword));
        }
        return urls;
    }

    private String encode(String keyword) {
        // URLEncoder emits '+' for spaces, but in URL *path* segments '+' is a
        // literal plus, not a space (live site expects %20).
        return URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Fetches the search HTML for one URL: direct GET first, falling back to the
     * local FlareSolverr proxy when the direct response is unusable (non-2xx
     * exception, blank body, or a Cloudflare challenge page).
     *
     * @throws IllegalStateException when both paths fail, so the caller treats
     *         the query as failed (warn + skip)
     */
    private String fetchHtml(String url) {
        String direct = tryDirectGet(url);
        if (present(direct) && !CHALLENGE_BODY.matcher(direct).find()) {
            return direct;
        }
        String proxied = fetchViaFlaresolverr(url);
        if (present(proxied)) {
            return proxied;
        }
        throw new IllegalStateException("direct fetch and FlareSolverr fallback both failed");
    }

    /** Direct GET with browser-like headers; returns null on any failure (incl. 403). */
    private String tryDirectGet(String url) {
        try {
            // URI overload: the String overload pre-encodes via DefaultUriBuilderFactory
            // (TEMPLATE_AND_VALUES), which would turn "%20" into "%2520" in the path.
            return webClient.get()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(REQUEST_TIMEOUT);
        } catch (Exception e) {
            log.debug("Jobleads direct GET failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * POSTs {@code {cmd:"request.get", url, maxTimeout:60000}} to the local
     * FlareSolverr service and returns the {@code solution.response} HTML.
     * Returns null on any failure (unreachable, status != ok, blank/malformed
     * response) so callers can report the query as failed.
     */
    private String fetchViaFlaresolverr(String url) {
        try {
            Map<String, Object> payload = Map.of(
                    "cmd", "request.get",
                    "url", url,
                    "maxTimeout", 60000);
            String body = webClient.post()
                    .uri(flaresolverrBaseUrl)
                    .header("User-Agent", USER_AGENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(FLARESOLVERR_TIMEOUT);
            if (body == null || body.isBlank()) {
                log.warn("FlareSolverr returned an empty response for {}", url);
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            if (!"ok".equals(root.path("status").asText(""))) {
                log.warn("FlareSolverr status for {}: {}", url, root.path("status").asText(""));
                return null;
            }
            String html = root.path("solution").path("response").asText(null);
            return (html == null || html.isBlank()) ? null : html;
        } catch (Exception e) {
            log.warn("FlareSolverr fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private List<RawAggregatorJob> parseCards(String html) {
        Document document = Jsoup.parse(html);
        List<RawAggregatorJob> jobs = new ArrayList<>();
        for (Element card : document.select("div[data-testid=\"search-job-card\"]")) {
            String externalId = card.attr("data-job-id").trim();
            if (externalId.isBlank()) {
                log.debug("Skipping Jobleads card without data-job-id");
                continue;
            }
            Element link = card.selectFirst("a[data-testid=\"search-job-card-link\"]");
            if (link == null) {
                log.debug("Skipping Jobleads card {} without link", externalId);
                continue;
            }
            Element salaryChip = card.selectFirst("[data-testid=\"job-card-chip-salary\"]");
            Salary salary = parseSalary(salaryChip == null ? null : salaryChip.text());
            jobs.add(new RawAggregatorJob(
                    externalId,
                    link.text().trim(),
                    cardText(card, "search-job-card-company-name"),
                    extractLocation(card),
                    null, // no description on search page — enriched later
                    absoluteUrl(link.attr("href")),
                    parsePostedDate(cardText(card, "job-card-date")),
                    salary.min(),
                    salary.max(),
                    salary.currency(),
                    null));
        }
        return jobs;
    }

    /**
     * List cards render location as the bullet suffix on the company line
     * ({@code OpenAI, Inc. • München}) — the {@code job-card-chip-location}
     * testid only exists inside the preview panel, not the search card.
     */
    private String extractLocation(Element card) {
        Element companyLine = card.selectFirst("[data-testid=\"search-job-card-company\"]");
        if (companyLine != null) {
            String text = companyLine.text();
            int bullet = text.indexOf('•');
            if (bullet >= 0) {
                String location = text.substring(bullet + 1).trim();
                if (!location.isEmpty()) {
                    return location;
                }
            }
        }
        return cardText(card, "job-card-chip-location");
    }

    private String cardText(Element card, String testId) {
        Element element = card.selectFirst("[data-testid=\"" + testId + "\"]");
        if (element == null) {
            return null;
        }
        String value = element.text().trim();
        return value.isBlank() ? null : value;
    }

    private String absoluteUrl(String href) {
        if (!present(href)) {
            return null;
        }
        String trimmed = href.trim();
        return trimmed.startsWith("/") ? SITE_ORIGIN + trimmed : trimmed;
    }

    /** Parses chips like "EUR 120.000 - 170.000" (German thousands dots). Malformed → all nulls. */
    private Salary parseSalary(String chipText) {
        if (!present(chipText)) {
            return new Salary(null, null, null);
        }
        String value = chipText.trim();
        String currency = null;
        Matcher currencyMatcher = CURRENCY_PREFIX.matcher(value);
        if (currencyMatcher.find()) {
            currency = currencyMatcher.group(1);
            value = value.substring(currencyMatcher.end()).trim();
        }
        String[] parts = value.split("-");
        BigDecimal min = number(parts[0]);
        BigDecimal max = parts.length > 1 ? number(parts[parts.length - 1]) : null;
        if (min == null && max == null) {
            return new Salary(null, null, null);
        }
        return new Salary(min, max, currency);
    }

    private BigDecimal number(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "14 Tage+" / "14 Tage" → N days ago; "Heute" → today; "Gestern" → yesterday; else null. */
    private LocalDate parsePostedDate(String text) {
        if (!present(text)) {
            return null;
        }
        String value = text.trim();
        if (value.equalsIgnoreCase("Heute")) {
            return LocalDate.now();
        }
        if (value.equalsIgnoreCase("Gestern")) {
            return LocalDate.now().minusDays(1);
        }
        Matcher matcher = DAYS_AGO.matcher(value);
        if (matcher.find()) {
            try {
                return LocalDate.now().minusDays(Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private void pause(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String configString(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : value.toString();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private record Salary(BigDecimal min, BigDecimal max, String currency) {}
}
