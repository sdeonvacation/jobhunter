package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AiAggregatorStrategy implements FetchStrategy {

    private static final String EXTRACTION_PROMPT = """
            Extract job listings from this HTML page. For each job return a JSON array of objects with these fields:
            - title: job title
            - companyName: company name
            - location: job location (or "Berlin" if not specified)
            - description: brief description snippet (max 200 chars)
            - applyUrl: the apply/detail URL (absolute URL)
            
            Return ONLY a valid JSON array, no markdown or explanation. If no jobs found, return [].
            """;

    /**
     * Upper bound on the prepared HTML handed to the model. Configurable per source via
     * the {@code maxHtmlChars} config key.
     */
    static final int DEFAULT_MAX_HTML_CHARS = 100_000;

    /** Elements that never carry job data but dominate page bytes (inline CSS, trackers, images). */
    private static final String NOISE_SELECTOR =
            "script, style, noscript, svg, iframe, link, meta, picture, img";

    /** Attributes that inflate the payload without contributing to extraction. */
    private static final List<String> NOISE_ATTRIBUTES = List.of("srcset", "sizes", "style", "class", "id");

    private final WebClient webClient;
    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;

    public AiAggregatorStrategy(WebClient webClient, AiProvider aiProvider) {
        this.webClient = webClient;
        this.aiProvider = aiProvider;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public boolean supports(AtsType type) {
        return false;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String url = (String) context.config().get("url");

        if (url == null || url.isBlank()) {
            return FetchResult.error("No URL configured in context", elapsed(start));
        }

        if (!aiProvider.isAvailable()) {
            log.debug("AI provider not available");
            return FetchResult.error("AI provider not available", elapsed(start));
        }

        try {
            log.info("Fetching HTML from {} for AI extraction", url);
            String html = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (html == null || html.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            String preparedHtml = prepareHtml(html, url, context.config());
            log.debug("Sending {} chars of prepared HTML to AI for extraction", preparedHtml.length());

            String aiResponse = aiProvider.generateExtraction(EXTRACTION_PROMPT, preparedHtml);

            List<AiExtractedJob> extracted = parseAiResponse(aiResponse);
            if (extracted.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = extracted.stream()
                    .limit(context.maxResults())
                    .map(this::toRawJob)
                    .toList();

            log.info("AI extracted {} jobs from {}", jobs.size(), url);
            return FetchResult.success(jobs, elapsed(start));

        } catch (Exception e) {
            log.error("AI aggregator fetch failed for {}: {}", url, e.getMessage());
            return FetchResult.error("AI extraction failed: " + e.getMessage(), elapsed(start));
        }
    }

    /**
     * Reduces a full page to a compact, job-relevant payload before it is sent to the model.
     *
     * <p>Truncating the raw document was the previous approach and it silently dropped every
     * listing below the byte cutoff: on image- and style-heavy pages (e.g. berlinstartupjobs)
     * the preamble alone consumed the whole budget. Here the noise is removed first, an
     * optional {@code contentSelector} narrows the payload to the listing container, and the
     * character limit is only a last-resort guard.
     */
    String prepareHtml(String html, String baseUri, Map<String, Object> config) {
        Document doc = Jsoup.parse(html, baseUri);
        doc.select(NOISE_SELECTOR).remove();

        Element root = doc.body() != null ? doc.body() : doc;

        String selector = configString(config, "contentSelector");
        if (selector != null && !selector.isBlank()) {
            Element scoped = root.selectFirst(selector);
            if (scoped != null) {
                root = scoped;
            } else {
                log.warn("contentSelector '{}' matched nothing; falling back to full page", selector);
            }
        }

        for (Element element : root.getAllElements()) {
            for (String attribute : NOISE_ATTRIBUTES) {
                element.removeAttr(attribute);
            }
        }

        String cleaned = root.outerHtml().replaceAll("\\s+", " ").trim();

        int maxChars = configInt(config, "maxHtmlChars", DEFAULT_MAX_HTML_CHARS);
        if (cleaned.length() > maxChars) {
            log.warn("Prepared HTML ({} chars) exceeds limit ({}); truncating and dropping trailing content",
                    cleaned.length(), maxChars);
            cleaned = cleaned.substring(0, maxChars);
        }
        return cleaned;
    }

    private List<AiExtractedJob> parseAiResponse(String response) {
        try {
            return parseJson(response);
        } catch (Exception primary) {
            // Model wrapped the array in prose: recover the complete array.
            String salvaged = extractJsonArray(response);
            if (salvaged != null) {
                try {
                    List<AiExtractedJob> parsed = parseJson(salvaged);
                    log.debug("Recovered JSON array from non-JSON AI response preamble");
                    return parsed;
                } catch (Exception ignored) {
                    // fall through to truncation repair
                }
            }
            // Model hit the output-token ceiling: keep the complete entries and close the array.
            String repaired = repairTruncatedJsonArray(response);
            if (repaired != null) {
                try {
                    List<AiExtractedJob> parsed = parseJson(repaired);
                    log.warn("Recovered {} listings from truncated AI extraction response", parsed.size());
                    return parsed;
                } catch (Exception ignored) {
                    // fall through and report the original failure
                }
            }
            log.warn("Failed to parse AI response as JSON: {}", primary.getMessage());
            throw new IllegalArgumentException("Malformed AI extraction JSON: " + primary.getMessage(), primary);
        }
    }

    private List<AiExtractedJob> parseJson(String response) throws Exception {
        String json = response == null ? "" : response.strip();
        // Strip markdown code fences if present
        if (json.startsWith("```")) {
            json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        List<AiExtractedJob> parsed = objectMapper.readValue(json, new TypeReference<>() {});
        return parsed != null ? parsed : List.of();
    }

    private String extractJsonArray(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        return start >= 0 && end > start ? response.substring(start, end + 1) : null;
    }

    /**
     * Salvages a JSON array that was cut off mid-object by the model's output-token ceiling.
     * Keeps everything up to the last complete object and appends the missing closers.
     */
    private String repairTruncatedJsonArray(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('[');
        if (start < 0) {
            return null;
        }
        String json = response.substring(start);

        int cutIndex = -1;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString && c == '}') {
                cutIndex = i;
            }
        }
        if (cutIndex <= 0) {
            return null;
        }

        String trimmed = json.substring(0, cutIndex + 1);
        int openBrackets = 0;
        int openBraces = 0;
        inString = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '[') openBrackets++;
                else if (c == ']') openBrackets--;
                else if (c == '{') openBraces++;
                else if (c == '}') openBraces--;
            }
        }

        StringBuilder repaired = new StringBuilder(trimmed);
        repaired.append("]".repeat(Math.max(0, openBrackets)));
        repaired.append("}".repeat(Math.max(0, openBraces)));
        return repaired.toString();
    }

    private RawAggregatorJob toRawJob(AiExtractedJob extracted) {
        String syntheticId = generateExternalId(extracted);
        return new RawAggregatorJob(
                syntheticId,
                extracted.title(),
                extracted.companyName(),
                extracted.location(),
                extracted.description(),
                extracted.applyUrl(),
                LocalDate.now(),
                null, null, null, null
        );
    }

    private String generateExternalId(AiExtractedJob job) {
        // L5 dedup_hash (computed from applyUrl) handles URL-based cross-source dedup;
        // keeping applyUrl out of the synthetic ID avoids fragmentation when the AI
        // returns a slightly different URL on re-extraction of the same listing.
        String content = String.join("|",
                job.title() != null ? job.title() : "",
                job.companyName() != null ? job.companyName() : "");
        return Integer.toHexString(content.hashCode());
    }

    private String configString(Map<String, Object> config, String key) {
        Object value = config != null ? config.get(key) : null;
        return value != null ? String.valueOf(value) : null;
    }

    private int configInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config != null ? config.get(key) : null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    record AiExtractedJob(
            String title,
            String companyName,
            String location,
            String description,
            String applyUrl
    ) {}
}
