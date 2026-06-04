package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class WorkableExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://apply.workable.com";
    private static final String LIST_PATH_TEMPLATE = "/api/v1/widget/accounts/%s?details=true";
    private static final String APPLY_URL_TEMPLATE = "https://apply.workable.com/%s/j/%s/";
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkableExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    WorkableExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.WORKABLE);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsSlug() != null && !endpoint.getAtsSlug().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        String slug = endpoint.getAtsSlug();
        Instant start = Instant.now();

        try {
            String url = baseUrl + String.format(LIST_PATH_TEMPLATE, slug);

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (response == null || response.isBlank()) {
                log.info("Workable [{}]: empty response", slug);
                return ExtractionResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode jobs = root.path("jobs");

            if (!jobs.isArray() || jobs.isEmpty()) {
                log.info("Workable [{}]: no jobs found", slug);
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> allJobs = new ArrayList<>();
            for (JsonNode node : jobs) {
                RawJobData job = mapJob(node, slug);
                if (job != null) {
                    allJobs.add(job);
                }
            }

            if (allJobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Workable [{}]: extracted {} jobs", slug, allJobs.size());
            return ExtractionResult.success(allJobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Workable [{}]: account not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (Exception e) {
            log.error("Workable [{}]: extraction failed: {}", slug, e.getMessage());
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawJobData mapJob(JsonNode node, String slug) {
        try {
            String shortcode = node.path("shortcode").asText(null);
            if (shortcode == null || shortcode.isBlank()) {
                return null;
            }

            String title = truncate(node.path("title").asText(null), 500);
            String location = truncate(buildLocation(node), 500);
            String applyUrl = String.format(APPLY_URL_TEMPLATE, slug, shortcode);

            // Parse created_at date
            String createdAt = node.path("created_at").asText(null);
            LocalDate postedDate = parseDate(createdAt);

            String rawJson = node.toString();

            return new RawJobData(
                    shortcode, title, location, null, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Workable: failed to map job: {}", e.getMessage());
            return null;
        }
    }

    private String buildLocation(JsonNode node) {
        String city = node.path("city").asText("");
        String state = node.path("state").asText("");
        String country = node.path("country").asText("");

        StringBuilder sb = new StringBuilder();
        if (!city.isBlank()) sb.append(city);
        if (!state.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(state);
        }
        if (!country.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country);
        }
        return sb.toString();
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            // Workable uses ISO format like "2024-01-15" or "2024-01-15T10:00:00Z"
            return LocalDate.parse(dateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
