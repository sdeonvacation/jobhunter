package dev.jobhunter.strategy.direct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AiPageStrategy implements FetchStrategy {

    private static final String SYSTEM_PROMPT = """
            Given this career page content, extract job postings as JSON. \
            Each job needs: title, location, applyUrl. \
            Only include actual job postings, not navigation or categories. \
            If no jobs are found, return {"jobs": []}.""";

    private static final int MAX_JOBS_PER_CHUNK = 40;
    private static final int MAX_CHUNK_CHARS = 3000;

    private static final Pattern JOB_HREF_PATTERN = Pattern.compile(
            "(?i)(job|position|career|apply|rolle|stelle|opening|vacancy|vakanc)"
    );

    private static final Set<String> REMOVE_TAGS = Set.of(
            "script", "style", "nav", "header", "footer", "form", "iframe", "svg",
            "noscript", "meta", "link"
    );

    private final WebClient webClient;
    private final AiProvider aiProvider;
    private final int maxContentChars;
    private final ObjectMapper objectMapper;

    public AiPageStrategy(
            WebClient webClient,
            AiProvider aiProvider,
            @Value("${ai-crawl.max-content-chars:8000}") int maxContentChars
    ) {
        this.webClient = webClient;
        this.aiProvider = aiProvider;
        this.maxContentChars = maxContentChars;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.CUSTOM);
    }

    @Override
    public String name() {
        return "ai-page";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();

        try {
            // Parse ats_slug as JSON config if present: {"post_body":{...}, "apply_base":"...", "headers":{...}, "jobs_path":"a.b.c"}
            String atsSlug = endpoint.getAtsSlug();
            String postBody = null;
            String applyBase = null;
            boolean jsonApi = false;
            String jobsPath = null;
            Map<String, String> extraHeaders = new HashMap<>();
            if (atsSlug != null && atsSlug.startsWith("{")) {
                try {
                    JsonNode cfg = objectMapper.readTree(atsSlug);
                    JsonNode pb = cfg.path("post_body");
                    if (!pb.isMissingNode()) postBody = objectMapper.writeValueAsString(pb);
                    JsonNode ab = cfg.path("apply_base");
                    if (!ab.isMissingNode() && !ab.isNull()) applyBase = ab.asText();
                    JsonNode ja = cfg.path("json_api");
                    if (!ja.isMissingNode() && ja.asBoolean()) jsonApi = true;
                    JsonNode jp = cfg.path("jobs_path");
                    if (!jp.isMissingNode() && !jp.isNull()) jobsPath = jp.asText();
                    JsonNode hn = cfg.path("headers");
                    if (hn.isObject()) {
                        hn.fields().forEachRemaining(e -> extraHeaders.put(e.getKey(), e.getValue().asText()));
                    }
                } catch (Exception ex) {
                    log.debug("AiPageStrategy: could not parse ats_slug as config for [{}]: {}", endpoint.getId(), ex.getMessage());
                }
            }

            // JSON API endpoints don't need AI — skip availability check and map directly
            if (jsonApi) {
                String content = fetchContent(endpoint.getUrl(), postBody, true, extraHeaders);
                if (content == null || content.isBlank()) return FetchResult.empty(elapsed(start));
                List<CandidateJob> candidates = extractCandidatesFromJson(
                        content, applyBase != null ? applyBase : endpoint.getUrl(), jobsPath);
                if (candidates.isEmpty()) return FetchResult.empty(elapsed(start));
                List<RawAggregatorJob> jobs = candidates.stream()
                        .filter(c -> c.title() != null && !c.title().isBlank())
                        .map(c -> new RawAggregatorJob(
                                generateExternalId(c.title(), c.applyUrl()),
                                c.title(), null, c.location(), null, c.applyUrl(), null, null, null, null, null))
                        .toList();
                if (jobs.isEmpty()) return FetchResult.empty(elapsed(start));
                log.info("JsonApi [{}]: extracted {} jobs", endpoint.getUrl(), jobs.size());
                return FetchResult.success(jobs, elapsed(start));
            }

            if (!aiProvider.isAvailable()) {
                log.debug("AI provider not available, skipping CUSTOM endpoint [{}]", endpoint.getId());
                return FetchResult.error("AI provider not available", elapsed(start));
            }

            String content = fetchContent(endpoint.getUrl(), postBody, false, extraHeaders);
            if (content == null || content.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            boolean isJson = isJsonContent(content);
            List<CandidateJob> candidates;
            Document htmlDoc = null;  // parsed once, reused if needed

            if (isJson) {
                candidates = extractCandidatesFromJson(content, applyBase != null ? applyBase : endpoint.getUrl());
            } else {
                htmlDoc = Jsoup.parse(content, endpoint.getUrl());
                removeNonContentElements(htmlDoc);
                candidates = extractCandidateJobs(htmlDoc, endpoint.getUrl());
            }

            List<AiExtractionResponse.AiJobEntry> allEntries = new ArrayList<>();
            Exception firstFailure = null;

            if (!candidates.isEmpty()) {
                for (List<CandidateJob> chunk : partition(candidates, MAX_JOBS_PER_CHUNK)) {
                    try {
                        collectEntries(formatCandidatesForAi(chunk), allEntries);
                    } catch (Exception e) {
                        if (firstFailure == null) firstFailure = e;
                        log.warn("GenericAI [{}]: chunk extraction failed: {}", endpoint.getUrl(), e.getMessage());
                    }
                }
            } else if (!isJson) {
                // HTML fallback: reuse already-parsed doc
                if (htmlDoc == null) {
                    htmlDoc = Jsoup.parse(content, endpoint.getUrl());
                    removeNonContentElements(htmlDoc);
                }
                for (String chunk : chunkText(extractBodyText(htmlDoc), MAX_CHUNK_CHARS)) {
                    try {
                        collectEntries(chunk, allEntries);
                    } catch (Exception e) {
                        if (firstFailure == null) firstFailure = e;
                        log.warn("GenericAI [{}]: chunk extraction failed: {}", endpoint.getUrl(), e.getMessage());
                    }
                }
            } else {
                // JSON content but no candidates from known wrappers — let AI try directly
                for (String chunk : chunkText(truncate(content, maxContentChars), MAX_CHUNK_CHARS)) {
                    try {
                        collectEntries(chunk, allEntries);
                    } catch (Exception e) {
                        if (firstFailure == null) firstFailure = e;
                        log.warn("GenericAI [{}]: chunk extraction failed: {}", endpoint.getUrl(), e.getMessage());
                    }
                }
            }

            if (allEntries.isEmpty()) {
                if (firstFailure != null) {
                    throw firstFailure;
                }
                return FetchResult.empty(elapsed(start));
            }

            List<AiExtractionResponse.AiJobEntry> uniqueEntries = allEntries.stream()
                    .collect(Collectors.toMap(
                            j -> (j.title() == null ? "" : j.title()) + "|" + (j.applyUrl() == null ? "" : j.applyUrl()),
                            j -> j,
                            (a, b) -> a,
                            LinkedHashMap::new))
                    .values()
                    .stream()
                    .toList();

            List<RawAggregatorJob> jobs = uniqueEntries.stream()
                    .filter(j -> j.title() != null && !j.title().isBlank())
                    .map(j -> mapToRawAggregatorJob(j, endpoint.getUrl()))
                    .toList();

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("GenericAI [{}]: extracted {} jobs", endpoint.getUrl(), jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException e) {
            log.error("GenericAI [{}]: HTTP {} - {}", endpoint.getUrl(), e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("GenericAI [{}]: extraction failed - {}", endpoint.getUrl(), e.getMessage(), e);
            return FetchResult.error(formatException(e), elapsed(start));
        }
    }

    private String formatException(Exception exception) {
        String exceptionType = exception instanceof WebClientRequestException
                ? WebClientRequestException.class.getSimpleName()
                : exception.getClass().getSimpleName();
        StringBuilder details = new StringBuilder(exceptionType);
        appendMessage(details, exception.getMessage());

        if (exception instanceof WebClientRequestException requestException) {
            details.append(" (URI: ").append(safeUri(requestException.getUri())).append(")");
        }

        Throwable cause = exception.getCause();
        Set<Throwable> seen = new HashSet<>();
        while (cause != null && seen.add(cause)) {
            details.append("; cause: ").append(cause.getClass().getSimpleName());
            appendMessage(details, cause.getMessage());
            break;
        }
        return details.toString();
    }

    private void appendMessage(StringBuilder details, String message) {
        if (message != null && !message.isBlank()) {
            details.append(": ").append(message);
        }
    }

    private String safeUri(URI uri) {
        if (uri == null) return "unknown";
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery() == null ? null : "[redacted]", null).toString();
        } catch (Exception ignored) {
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        }
    }

    /** Fetch via POST (if postBody non-null), JSON GET (if jsonApi), or HTML GET. */
    String fetchContent(String url, String postBody, boolean jsonApi) {
        return fetchContent(url, postBody, jsonApi, Map.of());
    }

    /** Fetch via POST (if postBody non-null), JSON GET (if jsonApi), or HTML GET. */
    String fetchContent(String url, String postBody, boolean jsonApi, Map<String, String> extraHeaders) {
        if (postBody != null) {
            return webClient.post()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.ibm.com/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(postBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
        }
        if (jsonApi) {
            return fetchJson(url, extraHeaders);
        }
        return fetchHtml(url);
    }

    /** Kept for tests that call the two-arg form directly. */
    String fetchContent(String url, String postBody) {
        return fetchContent(url, postBody, false);
    }

    /** True if content looks like a JSON object or array. */
    boolean isJsonContent(String content) {
        if (content == null) return false;
        String t = content.stripLeading();
        return t.startsWith("{") || t.startsWith("[");
    }

    /**
     * Extract CandidateJobs from a JSON API response.
     * Tries common array wrappers: hits, data, jobs, results, items, or root array.
     * Apply URLs are constructed from slug/url/link fields using applyBase when needed.
     */
    List<CandidateJob> extractCandidatesFromJson(String json, String applyBase) {
        return extractCandidatesFromJson(json, applyBase, null);
    }

    /**
     * Extract CandidateJobs from a JSON API response.
     * If jobsPath is non-null (e.g. "refineSearch.data.jobs"), navigates that dot-notation
     * path to find the array. Otherwise falls back to probing common wrapper keys.
     */
    List<CandidateJob> extractCandidatesFromJson(String json, String applyBase, String jobsPath) {
        List<CandidateJob> candidates = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode jobArray = null;

            // Explicit dot-notation path takes priority (e.g. "refineSearch.data.jobs")
            if (jobsPath != null && !jobsPath.isBlank()) {
                jobArray = resolvePath(root, jobsPath);
                if (jobArray == null || !jobArray.isArray()) {
                    log.debug("AiPageStrategy: jobs_path '{}' did not resolve to an array", jobsPath);
                    jobArray = null;
                }
            }

            // Generic wrapper fallback
            if (jobArray == null) {
                for (String key : List.of("hits", "data", "jobs", "results", "items")) {
                    JsonNode node = root.path(key);
                    if (node.isArray() && !node.isEmpty()) {
                        jobArray = node;
                        break;
                    }
                    // Elasticsearch: hits is an object containing a hits array
                    if (node.isObject()) {
                        JsonNode nested = node.path("hits");
                        if (nested.isArray() && !nested.isEmpty()) {
                            jobArray = nested;
                            break;
                        }
                    }
                }
            }
            if (jobArray == null && root.isArray() && !root.isEmpty()) jobArray = root;
            if (jobArray == null) return candidates;

            for (JsonNode job : jobArray) {
                // Elasticsearch: actual fields are under _source
                JsonNode src = job.has("_source") ? job.path("_source") : job;
                String title = firstNonNull(src, "job_title", "title", "name", "position", "jobTitle");
                if (title == null) continue;
                String location = firstNonNull(src, "city", "location", "locations", "office", "address", "country",
                                               "field_keyword_19", "field_keyword_05");
                String slugOrUrl = firstNonNull(src, "httpLink", "slug", "url", "link", "applyUrl", "apply_url", "externalUrl", "id");
                String applyUrl = buildApplyUrl(slugOrUrl, applyBase);
                candidates.add(new CandidateJob(title, location, applyUrl));
            }
        } catch (Exception e) {
            log.debug("AiPageStrategy: JSON candidate extraction failed: {}", e.getMessage());
        }
        return candidates;
    }

    private String buildApplyUrl(String slugOrUrl, String applyBase) {
        if (slugOrUrl == null) return null;
        if (slugOrUrl.startsWith("http://") || slugOrUrl.startsWith("https://")) return slugOrUrl;
        if (applyBase != null) {
            String base = applyBase.endsWith("/") ? applyBase : applyBase + "/";
            String slug = slugOrUrl.startsWith("/") ? slugOrUrl.substring(1) : slugOrUrl;
            return base + slug;
        }
        return slugOrUrl;
    }

    /**
     * Navigates a dot-notation path (e.g. "refineSearch.data.jobs") through a JsonNode tree.
     * Returns the node at the end of the path, or null if any segment is missing.
     */
    JsonNode resolvePath(JsonNode root, String dotPath) {
        JsonNode current = root;
        for (String segment : dotPath.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) return null;
            current = current.path(segment);
        }
        return (current == null || current.isMissingNode() || current.isNull()) ? null : current;
    }

    String firstNonNull(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode val = node.path(field);
            if (val.isMissingNode() || val.isNull()) continue;
            // Scalar value
            if (val.isValueNode()) {
                String text = val.asText().trim();
                if (!text.isEmpty()) return text;
            }
            // Array of objects — take first element's label/name/title or text
            if (val.isArray() && !val.isEmpty()) {
                JsonNode first = val.get(0);
                if (first.isValueNode()) {
                    String text = first.asText().trim();
                    if (!text.isEmpty()) return text;
                }
                for (String subField : new String[]{"label", "name", "title", "value", "text"}) {
                    JsonNode sub = first.path(subField);
                    if (!sub.isMissingNode() && !sub.isNull() && sub.isValueNode()) {
                        String text = sub.asText().trim();
                        if (!text.isEmpty()) return text;
                    }
                }
            }
        }
        return null;
    }

    String fetchHtml(String url) {
        return webClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    String fetchJson(String url) {
        return fetchJson(url, Map.of());
    }

    String fetchJson(String url, Map<String, String> extraHeaders) {
        var req = webClient.get()
                .uri(URI.create(url))
                .header("Accept", "application/json");
        for (var entry : extraHeaders.entrySet()) {
            req = req.header(entry.getKey(), entry.getValue());
        }
        return req.retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    void removeNonContentElements(Document doc) {
        for (String tag : REMOVE_TAGS) {
            doc.select(tag).remove();
        }
        // Only remove inline-hidden elements that are leaves (no child links)
        // SPAs often hide containers with [hidden] that hold SSR content
        doc.select("[style*=display:none]:not(:has(a)), [style*=display: none]:not(:has(a))").remove();
    }

    List<CandidateJob> extractCandidateJobs(Document doc, String baseUrl) {
        List<CandidateJob> candidates = new ArrayList<>();
        Elements links = doc.select("a[href]");

        for (Element link : links) {
            String href = link.attr("abs:href");
            if (href.isEmpty()) {
                href = link.attr("href");
            }

            if (!JOB_HREF_PATTERN.matcher(href).find()) {
                continue;
            }

            String title = link.text().trim();
            if (title.isEmpty() || title.length() < 3 || title.length() > 200) {
                continue;
            }

            // Skip navigation-like links
            if (isNavigationLink(title)) {
                continue;
            }

            // If link text is a generic CTA ("View Job", "View Position", etc.) the real title
            // lives in a nearby heading element — climb the DOM to find it.
            if (isGenericCtaText(title)) {
                String headingTitle = findNearestHeading(link);
                if (headingTitle != null && !headingTitle.isEmpty()) {
                    title = headingTitle;
                } else {
                    continue; // no heading found — skip rather than produce a bad title
                }
            }

            String location = extractLocationFromContext(link);
            String applyUrl = resolveUrl(href, baseUrl);

            candidates.add(new CandidateJob(title, location, applyUrl));
        }

        return candidates.stream().distinct().collect(Collectors.toList());
    }

    private boolean isNavigationLink(String text) {
        String lower = text.toLowerCase();
        return lower.equals("apply") || lower.equals("apply now")
                || lower.equals("careers") || lower.equals("jobs")
                || lower.equals("back") || lower.equals("home")
                || lower.equals("more") || lower.equals("read more")
                || lower.equals("learn more") || lower.equals("view all");
    }

    /**
     * Returns true if the link text is a generic CTA ("View Job", "View Position", etc.)
     * that does not contain the actual job title.
     */
    private boolean isGenericCtaText(String text) {
        String lower = text.toLowerCase();
        return lower.equals("view job") || lower.equals("view position")
                || lower.equals("view role") || lower.equals("view opening")
                || lower.equals("see details") || lower.equals("see job")
                || lower.equals("apply here") || lower.equals("find out more")
                || lower.equals("more details")
                || lower.startsWith("view job ") || lower.startsWith("view position ");
    }

    /**
     * Climbs up to 5 ancestor levels from {@code link} looking for the first h1/h2/h3/h4
     * that is a sibling or descendant of an ancestor — used when the link text is a generic
     * CTA and the real title lives in a nearby heading element.
     */
    private String findNearestHeading(Element link) {
        Element el = link;
        for (int i = 0; i < 5; i++) {
            el = el.parent();
            if (el == null) break;
            Element heading = el.selectFirst("h1, h2, h3, h4");
            if (heading != null) {
                String text = heading.text().trim();
                if (!text.isEmpty() && text.length() >= 3 && text.length() <= 200) {
                    return text;
                }
            }
        }
        return null;
    }

    private String extractLocationFromContext(Element link) {
        // Check sibling elements and parent for location text
        Element parent = link.parent();
        if (parent == null) {
            return null;
        }

        // Look for location in parent's other text nodes or sibling elements
        Elements siblings = parent.children();
        for (Element sibling : siblings) {
            if (sibling == link) continue;
            String text = sibling.text().trim();
            if (text.length() > 2 && text.length() < 100 && looksLikeLocation(text)) {
                return text;
            }
        }

        // Check grandparent's other children (common in list-based layouts)
        Element grandparent = parent.parent();
        if (grandparent != null) {
            for (Element child : grandparent.children()) {
                if (child == parent) continue;
                String text = child.text().trim();
                if (text.length() > 2 && text.length() < 100 && looksLikeLocation(text)) {
                    return text;
                }
            }
        }

        return null;
    }

    private boolean looksLikeLocation(String text) {
        String lower = text.toLowerCase();
        return lower.contains("remote") || lower.contains("berlin") || lower.contains("munich")
                || lower.contains("hamburg") || lower.contains("germany") || lower.contains("deutschland")
                || lower.contains("hybrid") || lower.contains("on-site") || lower.contains("onsite")
                || text.contains(",") && text.split(",").length <= 3;
    }

    String formatCandidatesForAi(List<CandidateJob> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pre-extracted job listings from career page. Confirm which are actual job postings and extract location if available:\n\n");
        sb.append("TITLE | LOCATION | URL\n");
        sb.append("------|----------|----\n");

        for (CandidateJob candidate : candidates) {
            sb.append(candidate.title());
            sb.append(" | ");
            sb.append(candidate.location() != null ? candidate.location() : "");
            sb.append(" | ");
            sb.append(candidate.applyUrl() != null ? candidate.applyUrl() : "");
            sb.append("\n");
        }

        return sb.toString();
    }

    String extractBodyText(Document doc) {
        // Try to find main content area first
        Element main = doc.selectFirst("main, [role=main], #content, .content, #main, .main");
        if (main != null) {
            return main.text();
        }
        // Fallback to body
        Element body = doc.body();
        return body != null ? body.text() : "";
    }

    private RawAggregatorJob mapToRawAggregatorJob(AiExtractionResponse.AiJobEntry entry, String endpointUrl) {
        String applyUrl = entry.applyUrl();
        if (applyUrl != null && !applyUrl.isBlank()) {
            applyUrl = resolveUrl(applyUrl, endpointUrl);
        }

        String externalId = generateExternalId(entry.title(), applyUrl);

        return new RawAggregatorJob(
                externalId,
                entry.title(),
                null, // companyName - comes from endpoint
                entry.location(),
                null, // no description from listing page
                applyUrl,
                null, // no postedDate
                null, null, null, // no salary info
                null  // no rawJson
        );
    }

    String generateExternalId(String title, String applyUrl) {
        String input = (title != null ? title : "") + "|" + (applyUrl != null ? applyUrl : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // Fallback: use hashCode
            return Integer.toHexString(input.hashCode());
        }
    }

    private String resolveUrl(String href, String baseUrl) {
        if (href == null || href.isBlank()) return null;
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (href.startsWith("/")) {
            // Absolute path - resolve against base
            try {
                java.net.URI base = java.net.URI.create(baseUrl);
                return base.getScheme() + "://" + base.getHost()
                        + (base.getPort() > 0 ? ":" + base.getPort() : "") + href;
            } catch (Exception e) {
                return href;
            }
        }
        // Relative path
        if (baseUrl.endsWith("/")) {
            return baseUrl + href;
        }
        return baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + href;
    }

    private String truncate(String content, int maxLength) {
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    private void collectEntries(String contentForAi, List<AiExtractionResponse.AiJobEntry> sink) {
        AiExtractionResponse response = aiProvider.extract(SYSTEM_PROMPT, contentForAi, AiExtractionResponse.class);
        if (response != null && response.jobs() != null) {
            sink.addAll(response.jobs());
        }
    }

    /**
     * Splits text into chunks of at most {@code maxChars}, breaking on newline
     * boundaries (newlines are kept). A single line longer than {@code maxChars}
     * is hard-split. Non-empty input always yields at least one non-empty chunk.
     */
    private List<String> chunkText(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.length() > maxChars) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
                int offset = 0;
                while (offset < line.length()) {
                    int end = Math.min(offset + maxChars, line.length());
                    chunks.add(line.substring(offset, end) + "\n");
                    offset = end;
                }
            } else if (current.length() + line.length() + 1 > maxChars && current.length() > 0) {
                chunks.add(current.toString());
                current = new StringBuilder(line).append('\n');
            } else {
                current.append(line).append('\n');
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    record CandidateJob(String title, String location, String applyUrl) {}
}
