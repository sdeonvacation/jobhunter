package dev.jobhunter.strategy.direct;

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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    public AiPageStrategy(
            WebClient webClient,
            AiProvider aiProvider,
            @Value("${ai-crawl.max-content-chars:8000}") int maxContentChars
    ) {
        this.webClient = webClient;
        this.aiProvider = aiProvider;
        this.maxContentChars = maxContentChars;
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.CUSTOM;
    }

    @Override
    public String name() {
        return "ai-page";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();

        if (!aiProvider.isAvailable()) {
            log.debug("AI provider not available, skipping CUSTOM endpoint [{}]", endpoint.getId());
            return FetchResult.empty(elapsed(start));
        }

        try {
            String html = fetchHtml(endpoint.getUrl());
            if (html == null || html.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            Document doc = Jsoup.parse(html, endpoint.getUrl());
            removeNonContentElements(doc);

            List<CandidateJob> candidates = extractCandidateJobs(doc, endpoint.getUrl());
            String contentForAi;

            if (!candidates.isEmpty()) {
                // Structured data found - format as table for AI to confirm/enrich
                contentForAi = formatCandidatesForAi(candidates);
            } else {
                // Fallback: send truncated body text for full extraction
                contentForAi = extractBodyText(doc);
            }

            if (contentForAi.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            contentForAi = truncate(contentForAi, maxContentChars);

            AiExtractionResponse response = aiProvider.extract(SYSTEM_PROMPT, contentForAi, AiExtractionResponse.class);
            if (response == null || response.jobs() == null || response.jobs().isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = response.jobs().stream()
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
            return FetchResult.error(e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(start));
        }
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

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    record CandidateJob(String title, String location, String applyUrl) {}
}
