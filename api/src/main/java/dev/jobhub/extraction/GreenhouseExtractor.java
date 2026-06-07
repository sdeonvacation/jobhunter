package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GreenhouseExtractor implements JobExtractor {

    private static final String API_URL = "https://boards-api.greenhouse.io/v1/boards/%s/jobs?content=true";
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
    private static final Map<String, String> HTML_ENTITIES = Map.of(
            "&amp;", "&",
            "&lt;", "<",
            "&gt;", ">",
            "&nbsp;", " ",
            "&quot;", "\"",
            "&#39;", "'",
            "&apos;", "'"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GreenhouseExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.GREENHOUSE);
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String url = String.format(API_URL, slug);
            String responseBody = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (responseBody == null || responseBody.isBlank()) {
                return ExtractionResult.empty(elapsed(start));
            }

            if (responseBody.length() > 10_000_000) {
                log.warn("Greenhouse [{}]: large response ({} MB)", slug, responseBody.length() / 1_048_576);
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // Some Greenhouse slugs return 200 OK with error JSON body instead of HTTP 404
            if (root.has("status") && root.path("status").isInt()) {
                int status = root.path("status").asInt();
                if (status >= 400) {
                    log.warn("Greenhouse [{}]: API returned error in body (status: {})", slug, status);
                    return ExtractionResult.empty(elapsed(start));
                }
            }

            JsonNode jobsNode = root.path("jobs");

            if (!jobsNode.isArray() || jobsNode.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (JsonNode jobNode : jobsNode) {
                RawJobData job = mapJob(jobNode);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Greenhouse [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Greenhouse [{}]: board not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Greenhouse [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Greenhouse [{}]: extraction failed - {} : {}", slug, e.getClass().getSimpleName(), e.getMessage(), e);
            return ExtractionResult.error(e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(start));
        }
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.GREENHOUSE
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    private RawJobData mapJob(JsonNode node) {
        try {
            String externalId = String.valueOf(node.path("id").asLong());
            String title = truncate(node.path("title").asText(null), 500);
            String location = truncate(node.path("location").path("name").asText(null), 500);
            String contentHtml = node.path("content").asText("");
            String description = stripHtml(contentHtml);
            String applyUrl = node.path("absolute_url").asText(null);
            String rawJson = node.toString();

            LocalDate postedDate = parseDate(node.path("updated_at").asText(null));

            return new RawJobData(
                    externalId, title, location, description, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Greenhouse: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = HTML_TAG_PATTERN.matcher(html).replaceAll("");
        for (Map.Entry<String, String> entry : HTML_ENTITIES.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(dateStr).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
